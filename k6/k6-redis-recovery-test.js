// Redis 완전 유실 → 복구(UBM-33) 정합성/성능 검증용 k6 시나리오.
//
// 시나리오: 재고 10,000 정책에 유저 20,000명(VU 20,000개, 1 VU = 1명)이 60초 동안
// ramp-up 하며 몰려든다. 실제 발급 플로우(POST /api/queue/join → GET /api/queue/status
// 폴링 → POST /coupon-policies/{id}/issue)를 그대로 태운다. Redis kill/revive/recover
// 재현은 이 스크립트 실행과 별도로(같은 세션에서) 수동/셸로 타이밍을 맞춰 진행한다.
//
// 로컬 실행: k6 run --env BASE_URL=http://localhost:8080 --env POLICY_ID=1 k6-redis-recovery-test.js
// Docker 실행(호스트에서 도는 앱을 대상으로): docker run --rm --add-host=host.docker.internal:host-gateway \
//   -v "<이 디렉터리>:/scripts" -e BASE_URL=http://host.docker.internal:8080 -e POLICY_ID=1 \
//   grafana/k6:latest run --summary-export=/scripts/summary.json /scripts/k6-redis-recovery-test.js

import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POLICY_ID = __ENV.POLICY_ID || '1';
const TOTAL_USERS = 20000;

// userId 1..20000 (미리 DB에 20000명 시딩해 둔 것과 1:1로 맞춘다)
const userIds = new SharedArray('userIds', function () {
    const arr = [];
    for (let i = 1; i <= TOTAL_USERS; i++) arr.push(i);
    return arr;
});

// ---- 커스텀 메트릭 ----
const joinWaiting = new Counter('join_waiting');
const joinSoldOut = new Counter('join_sold_out');
const joinDuplicate = new Counter('join_duplicate');
const joinOtherError = new Counter('join_other_error');
const joinDuration = new Trend('custom_join_duration');

const pollTimeout = new Counter('poll_timeout');
const pollSoldOut = new Counter('poll_sold_out');
const pollNotFound = new Counter('poll_not_found');
const admittedTotal = new Counter('admitted_total');
const timeToAdmit = new Trend('time_to_admit_ms');

const issueSuccess = new Counter('issue_success');
const issueError = new Counter('issue_error');
const issueDuration = new Trend('custom_issue_duration');

const endToEnd = new Trend('end_to_end_ms');

export const options = {
    scenarios: {
        rampup_20000_users: {
            // arrival-rate 대신 ramping-vus를 써서 "VU 1개 = 유저 1명"이 실제로 되게 한다.
            // 0 -> 20,000 VU로 60초 동안 램프업, VU마다 exec.vu.idInTest로 고유 userId를 매핑한다.
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '60s', target: 20000 },
            ],
            // 램프다운 단계가 없으므로 사실상 미사용이지만 명시해둔다.
            gracefulRampDown: '0s',
            // ramping-vus는 스테이지가 끝나도 VU가 폴링 중이면 계속 실행되게 둬야 한다 —
            // 폴링 루프가 최대 90초까지 걸릴 수 있어 넉넉히 잡는다.
            gracefulStop: '120s',
        },
    },
    // Redis 장애 구간에서 5xx가 대량 발생하는 것 자체가 테스트 대상이므로 임계치로 fail 처리하지 않는다.
    thresholds: {},
    setupTimeout: '30s',
};

// ramping-vus는 VU를 살려둔 채 default()를 테스트가 끝날 때까지 계속 재호출한다.
// VU 1개 = 유저 1명이 되려면 "이 VU는 자기 몫(유저 하나)의 여정을 이미 마쳤다"를 기억해야
// 한다 — 안 그러면 같은 userId로 join을 반복 호출해서 계속 409(중복)만 쌓인다.
// 이 변수는 모듈 스코프라 VU마다 격리된 인스턴스를 가지므로(k6가 VU별로 독립된 JS 컨텍스트를
// 준다) 별도 잠금 없이 안전하다.
let vuIterationDone = false;

export default function () {
    if (vuIterationDone) {
        // 이미 내 몫을 마친 VU 자리 — 테스트가 끝날 때까지 조용히 대기한다.
        sleep(5);
        return;
    }
    vuIterationDone = true;

    const startedAt = Date.now();
    const userId = userIds[(exec.vu.idInTest - 1) % userIds.length];

    let joinRes = null;
    const maxJoinAttempts = 15;
    for (let attempt = 0; attempt < maxJoinAttempts; attempt++) {
        joinRes = http.post(
            `${BASE_URL}/api/queue/join`,
            JSON.stringify({ policyId: Number(POLICY_ID), userId }),
            { headers: { 'Content-Type': 'application/json', 'Connection': 'keep-alive' }, tags: { name: 'join' } }
        );
        joinDuration.add(joinRes.timings.duration);

        if (joinRes.status === 200) {
            break;
        }

        const body = safeJson(joinRes.body);
        const msg = body && body.message ? body.message : '';
        if (joinRes.status === 400 || msg.includes('소진')) {
            joinSoldOut.add(1);
            return;
        } else if (joinRes.status === 409) {
            joinDuplicate.add(1);
            return;
        }

        // Redis 장애/복구 중(5xx / 일시 오류): 1초 대기 후 재시도
        sleep(1);
    }

    if (!joinRes || joinRes.status !== 200) {
        joinOtherError.add(1);
        return;
    }

    const joinBody = safeJson(joinRes.body);
    let activeToken = joinBody && joinBody.activeToken;

    if (!activeToken) {
        joinWaiting.add(1);
        // ---- 폴링 루프 ----
        const maxPolls = 90; // 대략 최대 90회 (백오프 감안 시 최장 수 분까지 대기)
        let admitted = false;
        let nextWaitSec = 1;
        for (let i = 0; i < maxPolls; i++) {
            sleep(nextWaitSec);

            const statusRes = http.get(
                `${BASE_URL}/api/queue/status?policyId=${POLICY_ID}&userId=${userId}`,
                { headers: { 'Connection': 'keep-alive' }, tags: { name: 'poll_status' } }
            );

            if (statusRes.status === 404) {
                // Redis 완전 유실로 대기열 ZSET 자체가 사라진 경우 (QueueNotRegisteredException -> 404)
                pollNotFound.add(1);
                return;
            }
            if (statusRes.status !== 200) {
                // Redis 장애 구간: 5xx/네트워크 에러. 계속 재시도한다(실제 클라이언트 동작과 동일).
                nextWaitSec = 1;
                continue;
            }
            const body = safeJson(statusRes.body);
            if (!body) {
                nextWaitSec = 1;
                continue;
            }

            if (body.status === 'ADMITTED') {
                activeToken = body.activeToken;
                admitted = true;
                admittedTotal.add(1);
                timeToAdmit.add(Date.now() - startedAt);
                break;
            }
            if (body.status === 'SOLD_OUT') {
                pollSoldOut.add(1);
                return;
            }
            if (body.status === 'NOT_FOUND' || body.status === 404) {
                pollNotFound.add(1);
                return;
            }

            // 서버가 준 retryAfterSeconds 기반 동적 백오프 (기본 1초, 최소 0.5초)
            nextWaitSec = (body.retryAfterSeconds && body.retryAfterSeconds > 0)
                ? Math.max(0.5, body.retryAfterSeconds)
                : 1;
        }
        if (!admitted) {
            pollTimeout.add(1);
            return;
        }
    } else {
        admittedTotal.add(1);
        timeToAdmit.add(Date.now() - startedAt);
    }

    // ---- 발급 호출 ----
    const issueRes = http.post(
        `${BASE_URL}/api/coupon-policies/${POLICY_ID}/issue`,
        JSON.stringify({ userId }),
        {
            headers: { 'Content-Type': 'application/json', 'Connection': 'keep-alive', 'X-Active-Token': activeToken },
            tags: { name: 'issue' },
        }
    );
    issueDuration.add(issueRes.timings.duration);

    if (issueRes.status === 202) {
        issueSuccess.add(1);
        endToEnd.add(Date.now() - startedAt);
    } else {
        issueError.add(1);
    }
}

function safeJson(body) {
    try {
        return JSON.parse(body);
    } catch (e) {
        return null;
    }
}
