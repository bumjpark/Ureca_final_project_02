// 선착순 쿠폰 발급 부하테스트 — 대기열 진입 → 폴링 → 발급까지 실제 유저 흐름을 그대로 흉내낸다.
//
// 환경변수(전부 선택):
//   POLICY_ID    대상 정책 id. 생략하면 setup()에서 재고 STOCK짜리 정책을 새로 만든다.
//   STOCK        POLICY_ID를 생략했을 때 새로 만들 정책의 재고(기본 10000)
//   PEAK         이번에 몰리는 사람 수(MODE=vus, 1인당 정확히 1번씩만 시도) 또는 초당 도착
//                요청 수(MODE=arrival, 계속 도착하는 트래픽이라 총 시도 횟수가 훨씬 많아짐).
//                기본 500
//   RAMP         0명(0건/s)에서 PEAK까지 올라가는 램프업 시간(기본 10s). MODE=vus에서 이걸
//                너무 짧게(사실상 0에 가깝게) 잡으면 수만 개 TCP 연결이 한꺼번에 몰려서
//                http_req_duration 자체가 초 단위로 튈 수 있다 — 이건 서버 버그가 아니라
//                "그 순간 진짜 동시접속 규모"를 그대로 보여주는 것(2026-08-29 조사 참고).
//   HOLD         PEAK를 유지하는 시간(기본 30s)
//   MODE         "vus"(PEAK명이 RAMP 동안 점진 유입돼 각자 한 번씩만 몰리는 "버스트") |
//                "arrival"(초당 도착률을 RAMP/HOLD 동안 유지하는 지속 부하). 기본 vus
//   MAX_USER_ID  userId를 배정할 상한 — 시딩된 users 테이블 행 수보다 크면 존재하지 않는
//                userId를 찍어서 발급 시 FK 위반(500)이 대량 발생한다. 생략하면 setup()에서
//                GET /api/users로 실제 시딩된 행 수를 물어봐서 자동으로 맞춘다(권장 — 굳이
//                직접 셀 필요 없음). 값을 주면 그 값을 그대로 믿고 쓴다.
//                userId는 무작위 추첨이 아니라 결정론적으로 배정한다(Docs/load-test/
//                k6-redis-recovery-test.js와 같은 방식) — 총 시도 횟수가 MAX_USER_ID
//                이하인 한 같은 userId가 두 번 뽑히는 일 자체가 없다.
//   QUEUE_LIMIT  이 정책의 대기열 처리 속도(초당 통과 인원)를 테스트 시작 전에 미리 조정하고 싶을 때
//   BASE_URL     API 베이스 URL(기본 http://app:8080 — docker-compose.loadtest.yml의 k6 서비스 기준)
//   폴링 재시도: WAITING 상태의 /api/queue/status가 일시적으로 실패하면 최대 5회 재시도하며,
//                정상 응답을 받으면 실패 카운트를 리셋한다. 5회 연속 실패한 경우에만
//                pollNon200으로 최종 이탈을 기록한다.
//
// 결과를 읽을 때 먼저 볼 것: infra_* 카운터 3개(infra_redis_unavailable /
// infra_db_connection_unavailable / infra_internal_error)가 전부 0이어야 한다. 이건 시나리오상
// 정상적인 실패(품절·중복·대기열 만료)가 아니라 "서버가 부하를 못 버텼다"는 신호라서,
// 하나라도 0이 아니면 그 회차의 지연/처리량 수치는 서버 정상 동작 기준값으로 쓸 수 없다.
//
// 대상 정책의 openAt이 미래여야 하는 검증(@Future) 때문에, POLICY_ID를 생략해 새로 만들 때는
// 서버 자신의 /api/health가 돌려주는 시각(TZ=Asia/Seoul 컨테이너 기준)을 기준으로 openAt을 잡고,
// 그 시각이 될 때까지 setup()에서 대기한다 — k6 실행 호스트 시계와 서버 컨테이너 시계가 다를 때
// "must be a future date" 오류로 전량 실패하는 걸 막기 위함이다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const MODE = __ENV.MODE || 'vus';
const PEAK = Number(__ENV.PEAK || 500);
const RAMP = __ENV.RAMP || '10s';
const HOLD = __ENV.HOLD || '30s';
const MAX_USER_ID_ENV = __ENV.MAX_USER_ID ? Number(__ENV.MAX_USER_ID) : null;
const STOCK = Number(__ENV.STOCK || 10000);
const QUEUE_LIMIT = __ENV.QUEUE_LIMIT ? Number(__ENV.QUEUE_LIMIT) : null;

// Docs/load-test/k6-redis-recovery-test.js(기존 Redis 장애 재현용 스크립트)의 세분화된
// 카운터 구성을 그대로 가져왔다 — "실패는 실패"로만 뭉뚱그리면 나중에 로그를 봐도 어느
// 단계에서 왜 실패했는지 구분이 안 된다.
const joinSoldOut = new Counter('join_sold_out');
const joinDuplicate = new Counter('join_duplicate');
const joinQueueFull = new Counter('join_queue_full');
const joinRateLimited = new Counter('join_rate_limited');
const joinOtherError = new Counter('join_other_error');

const pollNon200 = new Counter('poll_non_200');
const pollSoldOut = new Counter('poll_sold_out');
const pollExpired = new Counter('poll_expired');
const pollTimeout = new Counter('poll_timeout');
const admitWaitMs = new Trend('queue_admit_wait_ms');

const issued = new Counter('coupon_issued_total');
const issueSoldOut = new Counter('coupon_sold_out_total');
const issueDuplicated = new Counter('coupon_duplicated_total');
const issueOtherError = new Counter('coupon_other_failure_total');

// 인프라 장애(서버가 원인을 특정해 503으로 알려준 것) — 어느 단계에서 나든 여기로 모은다.
const infraRedisDown = new Counter('infra_redis_unavailable');
const infraDbPoolExhausted = new Counter('infra_db_connection_unavailable');
const infraInternalError = new Counter('infra_internal_error');

// 서버가 ErrorResponse.errorCode로 실패 원인을 알려준다.
function errorCodeOf(res) {
  const body = safeJson(res.body);
  return body && body.errorCode ? body.errorCode : null;
}

// 어느 단계에서든 공통으로 처리해야 하는 인프라 장애 코드를 집계한다.
function countIfInfraFailure(code) {
  if (code === 'REDIS_UNAVAILABLE') {
    infraRedisDown.add(1);
    return true;
  }

  if (code === 'DB_CONNECTION_UNAVAILABLE') {
    infraDbPoolExhausted.add(1);
    return true;
  }

  if (code === 'INTERNAL_ERROR') {
    infraInternalError.add(1);
    return true;
  }

  return false;
}

export const options = {
  scenarios: {
    burst:
      MODE === 'arrival'
        ? {
            // 지속 부하
            executor: 'ramping-arrival-rate',
            startRate: 1,
            timeUnit: '1s',
            preAllocatedVUs: Math.min(PEAK, 2000),
            maxVUs: Math.max(PEAK, 200),
            stages: [
              { target: PEAK, duration: RAMP },
              { target: PEAK, duration: HOLD },
              { target: 0, duration: '5s' },
            ],
          }
        : {
            // 버스트: PEAK명이 몰려서 각자 딱 1번씩만 시도한다.
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
              { target: PEAK, duration: RAMP },
              { target: PEAK, duration: HOLD },
              { target: 0, duration: '5s' },
            ],

            gracefulRampDown: '1s',

            // 폴링 루프가 최대 60회까지 걸릴 수 있으므로 넉넉하게 잡는다.
            gracefulStop: '120s',
          },
  },

  thresholds: {
    // NFR-1(초과발급 0건)은 별도 정합성 검증 리포트로 확인
    http_req_failed: ['rate<0.5'],
  },
};

const OPEN_DELAY_SECONDS = 5;

// /api/health의 checkedAt을 서버 시간 기준으로 처리한다.
function parseServerNowAsUtcMs(checkedAt) {
  const m = checkedAt.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/,
  );

  if (!m) {
    throw new Error(
      `/api/health checkedAt 형식을 파싱할 수 없습니다: ${checkedAt}`,
    );
  }

  const [, y, mo, d, h, mi, s] = m.map(Number);

  return Date.UTC(y, mo - 1, d, h, mi, s);
}

function toLocalDateTime(utcMs) {
  const d = new Date(utcMs);
  const pad = (n) => String(n).padStart(2, '0');

  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` +
    `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
  );
}

export function setup() {
  let policyId = __ENV.POLICY_ID
    ? Number(__ENV.POLICY_ID)
    : null;

  if (!policyId) {
    const healthRes = http.get(
      `${BASE_URL}/api/health?deep=false`,
    );

    const serverNowMs =
      parseServerNowAsUtcMs(
        JSON.parse(healthRes.body).checkedAt,
      );

    const openAtMs =
      serverNowMs + OPEN_DELAY_SECONDS * 1000;

    const createRes = http.post(
      `${BASE_URL}/api/admin/coupon-policies`,
      JSON.stringify({
        title: `k6-burst-${Date.now()}`,
        couponType: 'FIXED',
        discountValue: 500,
        totalQuantity: STOCK,
        openAt: toLocalDateTime(openAtMs),
        closeAt: null,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
        },
      },
    );

    check(createRes, {
      '정책 생성 201': (r) => r.status === 201,
    });

    policyId = JSON.parse(createRes.body).id;

    // 오픈 시각까지 대기
    sleep(OPEN_DELAY_SECONDS + 1);
  }

  let maxUserId = MAX_USER_ID_ENV;

  if (!maxUserId) {
    // 실제 시딩된 유저 수 확인
    const usersRes = http.get(
      `${BASE_URL}/api/users?page=0&size=1`,
    );

    const totalUsers =
      JSON.parse(usersRes.body).totalElements;

    if (!totalUsers || totalUsers < 1) {
      throw new Error(
        '시딩된 유저를 확인할 수 없습니다(GET /api/users totalElements=0) — MAX_USER_ID를 직접 지정해주세요.',
      );
    }

    maxUserId = totalUsers;
  }

  // Redis 재고 키가 준비되었는지 더미 join으로 확인
  for (let attempt = 0; attempt < 20; attempt++) {
    const probe = http.post(
      `${BASE_URL}/api/queue/join`,
      JSON.stringify({
        policyId,
        userId: maxUserId,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
        },
        tags: {
          name: 'setup_probe',
        },
      },
    );

    if (probe.status === 200) {
      break;
    }

    sleep(1);
  }

  if (QUEUE_LIMIT) {
    http.patch(
      `${BASE_URL}/api/admin/queue/limit`,
      JSON.stringify({
        policyId,
        limit: QUEUE_LIMIT,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
        },
      },
    );
  }

  console.log(
    `[setup] policyId=${policyId} stock=${STOCK} mode=${MODE} peak=${PEAK} maxUserId=${maxUserId}`,
  );

  return {
    policyId,
    maxUserId,
  };
}

function safeJson(body) {
  try {
    return JSON.parse(body);
  } catch (e) {
    return null;
  }
}

// ramping-vus에서는 VU 하나가 자신의 여정을 한 번만 실행하도록 한다.
let hasRun = false;

export default function (data) {
  const policyId = data.policyId;

  if (MODE !== 'arrival') {
    if (hasRun) {
      sleep(5);
      return;
    }

    hasRun = true;
  }

  // 결정론적인 userId 배정
  const userId =
    MODE === 'arrival'
      ? (exec.scenario.iterationInTest % data.maxUserId) + 1
      : ((exec.vu.idInTest - 1) % data.maxUserId) + 1;

  // ------------------------------------------------------------
  // 1. 대기열 진입
  // ------------------------------------------------------------

  const joinRes = http.post(
    `${BASE_URL}/api/queue/join`,
    JSON.stringify({
      policyId,
      userId,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        name: 'queue_join',
      },
    },
  );

  if (joinRes.status !== 200) {
    const code = errorCodeOf(joinRes);

    if (!countIfInfraFailure(code)) {
      if (
        code === 'ALREADY_ISSUED' ||
        joinRes.status === 409
      ) {
        joinDuplicate.add(1);
      } else if (code === 'OUT_OF_STOCK') {
        joinSoldOut.add(1);
      } else if (code === 'QUEUE_FULL') {
        joinQueueFull.add(1);
      } else if (code === 'TOO_MANY_REQUESTS') {
        joinRateLimited.add(1);
      } else if (
        code === null &&
        ((safeJson(joinRes.body) || {}).message || '').match(
          /소진|SOLD/,
        )
      ) {
        joinSoldOut.add(1);
      } else {
        joinOtherError.add(1);
      }
    }

    sleep(0.1);
    return;
  }

  let body = safeJson(joinRes.body) || {};
  const joinedAt = Date.now();
  let activeToken = body.activeToken;

  // ------------------------------------------------------------
  // 2. 대기열 폴링
  // ------------------------------------------------------------

  // WAITING이면 ADMITTED/SOLD_OUT/EXPIRED 중 하나로 끝날 때까지
  // 서버가 알려준 재시도 간격대로 폴링한다.
  //
  // 중요:
  // 폴링 요청 하나가 일시적으로 실패했다고 즉시 유저 여정을 종료하지 않는다.
  //
  // 예:
  //
  //   poll → 500
  //          ↓
  //       0.2초 후 재시도
  //          ↓
  //       0.4초 후 재시도
  //          ↓
  //       정상 200
  //          ↓
  //       다시 WAITING
  //
  // 정상 응답을 받으면 실패 카운터는 다시 0으로 초기화한다.
  //
  // 이렇게 해야 일시적인 네트워크 지연/5xx 때문에
  // k6가 실제 대기열 사용자를 "가짜 이탈"시키는 것을 방지할 수 있다.

  const MAX_POLLS = 60;

  // 하나의 폴링 요청이 실패했을 때 최대 재시도 횟수
  const POLL_FAILURE_RETRIES = 5;

  // 지수 백오프
  //
  // 1회 실패 → 0.2초
  // 2회 실패 → 0.4초
  // 3회 실패 → 0.8초
  // 4회 실패 → 1.6초
  // 5회 실패 → 최대 2초
  const POLL_RETRY_BASE_SECONDS = 0.2;
  const POLL_RETRY_MAX_SECONDS = 2;

  let attempts = 0;
  let pollFailureRetries = 0;

  while (
    body.status === 'WAITING' &&
    attempts < MAX_POLLS
  ) {
    // 서버가 알려준 정상 폴링 간격
    sleep(
      Math.max(
        0.2,
        body.retryAfterSeconds || 1,
      ),
    );

    const statusRes = http.get(
      `${BASE_URL}/api/queue/status?policyId=${policyId}&userId=${userId}`,
      {
        tags: {
          name: 'queue_status',
        },
      },
    );

    // ----------------------------------------------------------
    // 폴링 HTTP 실패
    // ----------------------------------------------------------

    if (statusRes.status !== 200) {
      const code = errorCodeOf(statusRes);

      // Redis/DB 등 명시적인 인프라 장애는 기존대로 집계
      const isInfraFailure =
        countIfInfraFailure(code);

      // 바로 포기하지 않고 최대 5회 재시도
      if (
        pollFailureRetries <
        POLL_FAILURE_RETRIES
      ) {
        pollFailureRetries += 1;

        // 짧은 지수 백오프
        //
        // 0.2 → 0.4 → 0.8 → 1.6 → 2.0
        const retryDelay = Math.min(
          POLL_RETRY_MAX_SECONDS,
          POLL_RETRY_BASE_SECONDS *
            Math.pow(
              2,
              pollFailureRetries - 1,
            ),
        );

        sleep(retryDelay);

        continue;
      }

      // 최대 재시도까지 실패했을 때만
      // 최종 폴링 실패로 기록
      //
      // 인프라 장애는 이미 infra_* 카운터에서
      // 별도로 집계했기 때문에 pollNon200과
      // 중복 집계하지 않는다.
      if (!isInfraFailure) {
        pollNon200.add(1);
      }

      return;
    }

    // ----------------------------------------------------------
    // HTTP 200이지만 JSON이 비정상인 경우
    // ----------------------------------------------------------

    const nextBody =
      safeJson(statusRes.body);

    if (
      !nextBody ||
      !nextBody.status
    ) {
      // 이 경우도 일시적인 응답 오류로 보고
      // 최대 5회까지 재시도
      if (
        pollFailureRetries <
        POLL_FAILURE_RETRIES
      ) {
        pollFailureRetries += 1;

        const retryDelay = Math.min(
          POLL_RETRY_MAX_SECONDS,
          POLL_RETRY_BASE_SECONDS *
            Math.pow(
              2,
              pollFailureRetries - 1,
            ),
        );

        sleep(retryDelay);

        continue;
      }

      // 5회 재시도 후에도 정상 응답을 못 받은 경우
      pollNon200.add(1);

      return;
    }

    // ----------------------------------------------------------
    // 정상적인 폴링 응답
    // ----------------------------------------------------------

    // 정상 응답을 받았으므로 실패 재시도 횟수 초기화
    pollFailureRetries = 0;

    body = nextBody;
    activeToken = body.activeToken;

    attempts += 1;
  }

  // ------------------------------------------------------------
  // 3. 대기열 종료 상태 처리
  // ------------------------------------------------------------

  if (body.status === 'SOLD_OUT') {
    pollSoldOut.add(1);
    return;
  }

  if (body.status === 'EXPIRED') {
    pollExpired.add(1);
    return;
  }

  // 60회까지 폴링했는데도 WAITING이면 timeout
  if (
    body.status !== 'ADMITTED' ||
    !activeToken
  ) {
    pollTimeout.add(1);
    return;
  }

  // 대기열 진입부터 ADMITTED까지 걸린 시간
  admitWaitMs.add(
    Date.now() - joinedAt,
  );

  // ------------------------------------------------------------
  // 4. 실제 쿠폰 발급
  // ------------------------------------------------------------

  const issueRes = http.post(
    `${BASE_URL}/api/coupon-policies/${policyId}/issue`,
    JSON.stringify({
      userId,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Active-Token': activeToken,
      },
      tags: {
        name: 'coupon_issue',
      },
    },
  );

  if (issueRes.status === 202) {
    issued.add(1);
    return;
  }

  const issueCode =
    errorCodeOf(issueRes);

  if (
    countIfInfraFailure(issueCode)
  ) {
    sleep(0.1);
    return;
  }

  // ADMITTED까지 갔는데도
  // 재고 소진 또는 중복 발급
  if (
    issueCode === 'ALREADY_ISSUED'
  ) {
    issueDuplicated.add(1);
  } else if (
    issueCode === 'OUT_OF_STOCK'
  ) {
    issueSoldOut.add(1);
  } else if (
    issueCode === null &&
    (issueRes.body || '').match(
      /DUPLICATE|중복/,
    )
  ) {
    issueDuplicated.add(1);
  } else if (
    issueCode === null &&
    issueRes.status === 409
  ) {
    issueSoldOut.add(1);
  } else {
    issueOtherError.add(1);
  }

  sleep(0.1);
}