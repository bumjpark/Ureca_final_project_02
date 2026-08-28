// k6 부하 시나리오 — 선착순 쿠폰 발급, 20,000명 동시 트래픽 재현
//
// 공식 기준: 재고 10,000장 / 동시 20,000 VU / ramp-up 60s → 초과 발급 0건 확인
//
// ── 도커에서 (권장, app 과 같은 네트워크) ────────────────────────────
//   docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d --build
//   docker compose -f docker-compose.yml -f docker-compose.loadtest.yml run --rm \
//     -e POLICY_ID=1 k6 run /scripts/burst-20k.js
//
// ── 호스트에서 (메모리 여유 있을 때) ─────────────────────────────────
//   ulimit -n 200000
//   k6 run -e BASE=http://localhost:8080 -e POLICY_ID=1 loadtest/burst-20k.js
//
// ── 옵션 ───────────────────────────────────────────────────────────
//   -e BASE=...            대상 (기본 http://app:8080)
//   -e POLICY_ID=1         쿠폰 정책 id. 생략하면 setup 에서 재고 10,000 정책을 새로 만든다
//   -e PEAK=20000          최대 동시 VU
//   -e RAMP=60s / HOLD=60s ramp-up / 피크 유지
//   -e MODE=vus | arrival  VU 기반(기본) vs 도착률 기반
//   -e STOCK=10000         POLICY_ID 생략 시 만들 재고

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// docker-compose 의 k6 서비스는 BASE_URL=http://coupon-app:8080 을 준다 (호스트 NAT 우회).
// 호스트에서 직접 돌릴 땐 -e BASE=http://localhost:8080 으로.
const BASE = __ENV.BASE_URL || __ENV.BASE || 'http://app:8080';
const PEAK = Number(__ENV.PEAK || 20000);
const RAMP = __ENV.RAMP || '60s';
const HOLD = __ENV.HOLD || '60s';
const MODE = __ENV.MODE || 'vus';
const STOCK = Number(__ENV.STOCK || 10000);
// arrival 모드에서 초당 유입 요청 수 (기본: PEAK 를 30초에 나눠 유입)
const ARRIVAL_RATE = Number(__ENV.ARRIVAL_RATE || Math.ceil(PEAK / 30));

const issued202 = new Counter('r_issued_202');
const dup409 = new Counter('r_duplicated_409');
const soldout = new Counter('r_soldout');
const queueFull = new Counter('r_queue_full_503');
const rateLimited = new Counter('r_rate_limited_429');
const admitWait = new Trend('t_admit_wait_ms', true);

const vusScenario = {
  executor: 'ramping-vus',
  startVUs: 0,
  stages: [
    { duration: RAMP, target: PEAK },
    { duration: HOLD, target: PEAK },
    { duration: '10s', target: 0 },
  ],
  gracefulStop: '15s',
};

const arrivalScenario = {
  executor: 'ramping-arrival-rate',
  startRate: 0,
  timeUnit: '1s',
  preAllocatedVUs: Math.min(PEAK, 2000),
  maxVUs: Math.min(PEAK, 6000),
  stages: [
    { duration: RAMP, target: ARRIVAL_RATE }, // 초당 ARRIVAL_RATE 건까지 유입 증가
    { duration: HOLD, target: ARRIVAL_RATE },
    { duration: '10s', target: 0 },
  ],
};

export const options = {
  // 20k VU 메모리 절감: 바디는 필요한 요청에서만 responseType:'text' 로 받는다
  discardResponseBodies: true,
  scenarios: { burst: MODE === 'arrival' ? arrivalScenario : vusScenario },
  thresholds: {
    // 핵심 지표: 발급 접수가 재고를 절대 넘지 않아야 한다 (초과 발급 0건)
    r_issued_202: [{ threshold: `count<=${STOCK}`, abortOnFail: false }],
    // 재고 소진 후 요청은 4xx 로 거절되는 게 정상이라 http_req_failed 는 임계치를 두지 않는다
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };
const WITH_BODY = { responseType: 'text' };

// ── setup: 정책 준비 (POLICY_ID 없으면 새로 생성) ──────────────────
export function setup() {
  let policyId = __ENV.POLICY_ID ? Number(__ENV.POLICY_ID) : null;

  if (!policyId) {
    // 서버 시간(LocalDateTime, KST wall-clock) 기준 +20초 — k6 실행 환경의
    // 타임존과 무관하게 항상 "미래"가 되도록 서버가 알려준 시각을 쓴다.
    const h = http.get(`${BASE}/api/health`, { responseType: 'text' });
    const serverNow = new Date(JSON.parse(h.body).checkedAt + 'Z');
    const openAt = new Date(serverNow.getTime() + 20_000).toISOString().slice(0, 19);
    const res = http.post(
      `${BASE}/api/admin/coupon-policies`,
      JSON.stringify({
        title: `k6 부하테스트 재고 ${STOCK} (${new Date().toISOString().slice(11, 19)})`,
        couponType: 'FIXED',
        discountValue: 5000,
        totalQuantity: STOCK,
        openAt,
        closeAt: '2026-12-31T23:59:59',
      }),
      { headers: JSON_HEADERS, responseType: 'text' },
    );
    if (res.status !== 201) {
      throw new Error(`정책 생성 실패 (${res.status}): ${res.body}`);
    }
    policyId = JSON.parse(res.body).id;
    console.log(`\n▶ 정책 #${policyId} 생성 · 재고 ${STOCK} · 오픈 ${openAt} — 20초 후 오픈\n`);
    sleep(22); // @Future 오픈 시각 도달 대기
  }

  // 대기열 통과 속도(초당 정원) 상향 — 안 올리면 20k VU 가 큐에서 1~2분씩 밀려
  // 폴링 상한(40회)에 걸려 발급을 포기하고, 재고가 안 빠진다.
  // QUEUE_LIMIT=0 으로 주면 이 단계를 건너뛴다(서버 기본값 사용).
  const queueLimit = Number(__ENV.QUEUE_LIMIT ?? 5000);
  if (queueLimit > 0) {
    const r = http.patch(
      `${BASE}/api/admin/queue/limit`,
      JSON.stringify({ policyId, limit: queueLimit }),
      { headers: JSON_HEADERS, responseType: 'text' },
    );
    console.log(`▶ 대기열 limit = ${queueLimit}/s (${r.status})`);
  }

  // Redis 재고 키 초기화(RedisAutoRecoveryScheduler, 최대 ~5s) 여유
  sleep(6);
  return { policyId };
}

export default function (data) {
  const policyId = data.policyId;
  const userId = 1 + Math.floor(Math.random() * 1_000_000);

  // 1. 대기열 등록
  const join = http.post(
    `${BASE}/api/queue/join`,
    JSON.stringify({ policyId, userId }),
    { headers: JSON_HEADERS, ...WITH_BODY, tags: { name: 'queue/join' } },
  );
  if (join.status === 429) { rateLimited.add(1); return; }
  if (join.status === 409) { dup409.add(1); return; }
  if (join.status === 503) { queueFull.add(1); return; }
  if (join.status !== 200) return;

  const t0 = Date.now();
  let token = safeJson(join.body)?.activeToken || null;

  // 2. WAITING → status 폴링 (서버가 주는 retryAfterSeconds 존중)
  //    20k VU 는 큐에서 오래 밀리므로 상한을 넉넉히 (포기하면 재고가 안 빠짐)
  let attempts = 0;
  while (!token && attempts < 90) {
    attempts++;
    // name 태그로 그룹핑 — userId 가 URL 에 있어 이게 없으면 k6 가 VU 마다
    // 별도 time series 를 만들어 메모리가 폭증한다 (high-cardinality 경고).
    const st = http.get(
      `${BASE}/api/queue/status?policyId=${policyId}&userId=${userId}`,
      { ...WITH_BODY, tags: { name: 'queue/status' } },
    );
    if (st.status === 200) {
      const s = safeJson(st.body) || {};
      if (s.status === 'ADMITTED') { token = s.activeToken; break; }
      if (s.status === 'SOLD_OUT') { soldout.add(1); return; }
      if (s.status === 'EXPIRED') return;
      // 20k VU 가 동시에 폴링하면 그 자체가 부하다 — 최소 1초 간격 + 서버 권고치 존중
      sleep(Math.max(1, Number(s.retryAfterSeconds) || 1));
    } else if (st.status === 409) {
      dup409.add(1); return;
    } else {
      sleep(1);
    }
  }
  if (!token) return;
  admitWait.add(Date.now() - t0);

  // 3. 발급 (202 Accepted, 바디 불필요)
  const issue = http.post(
    `${BASE}/api/coupon-policies/${policyId}/issue`,
    JSON.stringify({ userId }),
    { headers: { ...JSON_HEADERS, 'X-Active-Token': token }, tags: { name: 'issue' } },
  );
  check(issue, { 'issue → 202': (r) => r.status === 202 });
  if (issue.status === 202) issued202.add(1);
  else if (issue.status === 409) dup409.add(1);
  else if (issue.status === 400) soldout.add(1);
}

export function teardown(data) {
  console.log(`\n■ 대상 정책 #${data.policyId} — 초과 발급 0건 최종 확인은 /admin/verification 검증 리포트로\n`);
}

function safeJson(body) {
  try { return JSON.parse(body); } catch (_) { return null; }
}

// 종료 요약에 함께 출력되는 커스텀 메트릭:
//   r_issued_202       발급 접수 성공(202) → STOCK 이하이면 초과 접수 방어 성공
//   r_duplicated_409   1인 1매 중복 차단
//   r_soldout          재고 소진 거절
//   r_queue_full_503   대기열 포화
//   r_rate_limited_429 유저별 초당 요청 제한
//   t_admit_wait_ms    join → 대기열 입장까지 걸린 시간
