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
// 단계(join/poll/issue)에서 왜 실패했는지 구분이 안 된다.
const joinSoldOut = new Counter('join_sold_out'); // 대기열 진입 시점에 이미 품절
const joinDuplicate = new Counter('join_duplicate'); // 이미 발급/대기 중인 userId로 재진입
const joinQueueFull = new Counter('join_queue_full'); // 대기열 정원 초과(503 QUEUE_FULL)
const joinRateLimited = new Counter('join_rate_limited'); // 연타 컷(429 TOO_MANY_REQUESTS)
const joinOtherError = new Counter('join_other_error');

const pollNon200 = new Counter('poll_non_200'); // 폴링 자체가 5xx/네트워크 오류
const pollSoldOut = new Counter('poll_sold_out'); // 대기 중 재고 소진으로 SOLD_OUT 종료
const pollExpired = new Counter('poll_expired'); // 대기열 등록이 만료됨(EXPIRED)
const pollTimeout = new Counter('poll_timeout'); // maxPolls를 넘도록 계속 WAITING
const admitWaitMs = new Trend('queue_admit_wait_ms');

const issued = new Counter('coupon_issued_total');
const issueSoldOut = new Counter('coupon_sold_out_total'); // ADMITTED까지 갔는데도 발급 시점에 품절
const issueDuplicated = new Counter('coupon_duplicated_total');
const issueOtherError = new Counter('coupon_other_failure_total');

// 인프라 장애(서버가 원인을 특정해 503으로 알려준 것) — 어느 단계에서 나든 여기로 모은다.
// 이 값이 0이 아니면 "서버가 부하를 못 버틴 것"이지 "테스트 시나리오상 정상적인 실패"가 아니다.
const infraRedisDown = new Counter('infra_redis_unavailable'); // 503 REDIS_UNAVAILABLE
const infraDbPoolExhausted = new Counter('infra_db_connection_unavailable'); // 503 DB_CONNECTION_UNAVAILABLE
const infraInternalError = new Counter('infra_internal_error'); // 500 INTERNAL_ERROR (미분류 = 진짜 버그 의심)

// 서버가 ErrorResponse.errorCode로 실패 원인을 알려준다(2026-08-29 추가). 예전엔 message
// 문자열을 부분 매칭해서 분류했는데, 그 방식은 문구가 조금만 바뀌어도 조용히 오분류되고
// 무엇보다 인프라 장애(Redis/DB 풀)를 "기타 실패"로 뭉개버렸다.
// errorCode가 없는(=아직 코드를 안 붙인) 응답은 null이 되고, 호출부가 기존처럼 문자열
// 매칭으로 넘어가도록 fallback을 남겨둔다.
function errorCodeOf(res) {
  const body = safeJson(res.body);
  return body && body.errorCode ? body.errorCode : null;
}

// 어느 단계에서든 공통으로 처리해야 하는 인프라 장애 코드를 집계한다.
// 집계했으면 true — 호출부는 단계별 분류를 건너뛰면 된다.
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
            // 지속 부하: RAMP 동안 목표 도착률까지 올리고 HOLD 동안 유지 — 총 요청 수는
            // "도착률 × 시간"이라 PEAK(초당 도착 수)보다 훨씬 많아진다. 사람 수를 정확히
            // 맞추고 싶다면 MODE=vus를 쓸 것.
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
            // "버스트": PEAK명이 몰려서 각자 딱 1번씩만 시도한다(사람 1명 = 시도 1번).
            // Docs/load-test/k6-redis-recovery-test.js(2026-08-27, 같은 재고/유저 규모에서
            // http_req_duration p95 1ms대를 실측한 스크립트)와 똑같이 ramping-vus + "VU당
            // 한 번만 실행" 가드를 쓴다 — RAMP 동안 PEAK명까지 점진적으로 유입시킨다.
            // (이전엔 shared-iterations로 전원을 사실상 동시에 투입했는데, 그게 20,000
            // TCP 연결이 한꺼번에 몰리는 것 자체의 비용을 만들어 http_req_duration이
            // 초 단위로 튀는 원인이었다 — 2026-08-29 조사 참고)
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
              { target: PEAK, duration: RAMP },
              { target: PEAK, duration: HOLD },
              { target: 0, duration: '5s' },
            ],
            // 램프업 막바지에 스케줄된 VU가 스테이지 종료와 겹쳐 자기 몫을 한 번도 못 해보고
            // 정리되는 경계 케이스 방지(2026-08-27 실측: 40,000 VU에서 정확히 1개 VU 누락).
            gracefulRampDown: '1s',
            // 폴링 루프가 최대 60회(백오프 포함 최장 수십 초)까지 걸릴 수 있어 넉넉히 잡는다.
            gracefulStop: '120s',
          },
  },
  thresholds: {
    // NFR-1(초과발급 0건)은 별도 정합성 검증 리포트로 확인 — 여기 임계값은 부하테스트 자체가
    // API 레벨에서 죽지 않고 도는지(타임아웃/5xx 폭주)만 거른다.
    http_req_failed: ['rate<0.5'],
  },
};

const OPEN_DELAY_SECONDS = 5;

// /api/health의 checkedAt(예: "2026-08-29T07:14:32.123456789")은 서버 컨테이너 자신의
// 시계(TZ=Asia/Seoul) 문자열이다. k6 컨테이너는 보통 UTC라 이 문자열을 그냥 `new Date(s)`로
// 파싱하면(로컬 타임존 해석 + 나노초 자리수에 따라 파싱 실패) 엉뚱한 값이나 Invalid Date가 나올
// 수 있어, 항상 Date.UTC로만 인코딩/디코딩해 "그 숫자 그대로"를 절대 계산 없이 왕복시킨다 —
// 우리는 이 값의 실제 시간대가 뭔지는 몰라도 되고, 그 값에 N초를 더한 문자열만 다시 만들면 된다.
function parseServerNowAsUtcMs(checkedAt) {
  const m = checkedAt.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  if (!m) {
    throw new Error(`/api/health checkedAt 형식을 파싱할 수 없습니다: ${checkedAt}`);
  }
  const [, y, mo, d, h, mi, s] = m.map(Number);
  return Date.UTC(y, mo - 1, d, h, mi, s);
}

function toLocalDateTime(utcMs) {
  // java.time.LocalDateTime이 파싱할 수 있는 "yyyy-MM-ddTHH:mm:ss" 형식(타임존 오프셋 없음).
  // parseServerNowAsUtcMs와 항상 짝으로 써야 한다 — UTC 메서드로만 왕복해야 인코딩이 맞는다.
  const d = new Date(utcMs);
  const pad = (n) => String(n).padStart(2, '0');
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` +
    `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
  );
}

export function setup() {
  let policyId = __ENV.POLICY_ID ? Number(__ENV.POLICY_ID) : null;

  if (!policyId) {
    const healthRes = http.get(`${BASE_URL}/api/health?deep=false`);
    const serverNowMs = parseServerNowAsUtcMs(JSON.parse(healthRes.body).checkedAt);
    const openAtMs = serverNowMs + OPEN_DELAY_SECONDS * 1000; // @Future 검증 통과 + 아래서 대기
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
      { headers: { 'Content-Type': 'application/json' } },
    );
    check(createRes, { '정책 생성 201': (r) => r.status === 201 });
    policyId = JSON.parse(createRes.body).id;

    // 오픈 시각까지 대기 — 안 그러면 초반 요청들이 전부 "아직 오픈 전" 실패로 낭비된다.
    // 위에서 openAt = 서버시각 + OPEN_DELAY_SECONDS로 잡았으니, 여기서는 시계 비교 없이
    // 그 초만큼 그냥 실제로 기다리면 된다(요청 왕복 지연만큼 살짝 여유 있게).
    sleep(OPEN_DELAY_SECONDS + 1);
  }

  let maxUserId = MAX_USER_ID_ENV;
  if (!maxUserId) {
    // 실제 시딩된 유저 행 수를 물어봐서 그 범위 안에서만 userId를 배정한다 — 존재하지 않는
    // userId로 발급을 시도하면 coupon_issue.user_id FK 제약 위반(500)이 대량으로 터진다.
    const usersRes = http.get(`${BASE_URL}/api/users?page=0&size=1`);
    const totalUsers = JSON.parse(usersRes.body).totalElements;
    if (!totalUsers || totalUsers < 1) {
      throw new Error('시딩된 유저를 확인할 수 없습니다(GET /api/users totalElements=0) — MAX_USER_ID를 직접 지정해주세요.');
    }
    maxUserId = totalUsers;
  }

  // 정책이 열려도 Redis 재고 키는 즉시 채워지지 않는다 — RedisAutoRecoveryScheduler가
  // 기본 5초 주기로 훑으면서 채워주는 구조라, 방금 연 정책은 그 다음 틱이 돌 때까지
  // "쿠폰 재고가 Redis에 초기화되지 않았습니다"(500)로 전량 실패할 수 있다(POLICY_ID를
  // 직접 지정한 경우도 막 오픈한 정책이면 마찬가지). 고정 시간만 더 자는 대신, 더미 조인으로
  // 실제 준비 여부를 확인하고 나서 본 실행을 시작한다. userId는 maxUserId(범위의 맨 끝)를
  // 써서, 본 실행의 반복 0번이 결정론적으로 배정받는 userId=1과 겹치지 않게 한다.
  for (let attempt = 0; attempt < 20; attempt++) {
    const probe = http.post(
      `${BASE_URL}/api/queue/join`,
      JSON.stringify({ policyId, userId: maxUserId }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup_probe' } },
    );
    if (probe.status === 200) break;
    sleep(1);
  }

  if (QUEUE_LIMIT) {
    http.patch(
      `${BASE_URL}/api/admin/queue/limit`,
      JSON.stringify({ policyId, limit: QUEUE_LIMIT }),
      { headers: { 'Content-Type': 'application/json' } },
    );
  }

  console.log(`[setup] policyId=${policyId} stock=${STOCK} mode=${MODE} peak=${PEAK} maxUserId=${maxUserId}`);
  return { policyId, maxUserId };
}

function safeJson(body) {
  try {
    return JSON.parse(body);
  } catch (e) {
    return null;
  }
}

// ramping-vus는 VU가 살아있는 한 default()를 스테이지가 끝날 때까지 계속 재호출한다 —
// "VU 1개 = 사람 1명"이 되려면 이 VU가 이미 자기 몫(유저 하나)의 여정을 마쳤음을 기억해야
// 한다(안 그러면 같은 userId로 계속 재시도해서 join_duplicate만 쌓인다). 모듈 스코프 변수라
// VU마다 격리된 인스턴스를 가지므로(k6가 VU별로 독립된 JS 컨텍스트를 준다) 잠금 없이 안전하다.
// MODE=arrival(ramping-arrival-rate)은 VU 풀을 계속 재사용하는 게 정상 동작이라 이 가드를
// 적용하지 않는다.
let hasRun = false;

export default function (data) {
  const policyId = data.policyId;

  if (MODE !== 'arrival') {
    if (hasRun) {
      sleep(5); // 이미 내 몫을 마친 VU 자리 — 테스트가 끝날 때까지 조용히 대기
      return;
    }
    hasRun = true;
  }

  // 무작위 추첨 대신 결정론적으로 매핑한다 — 같은 userId가 두 번 뽑히는 일 자체가 없다.
  // MODE=vus: 이 VU의 유일한 실행이므로 VU 번호 기준(재사용 안 되니 겹칠 걱정 없음).
  // MODE=arrival: VU가 재사용되므로 전역 반복 순번 기준이어야 겹치지 않는다.
  const userId = MODE === 'arrival'
    ? (exec.scenario.iterationInTest % data.maxUserId) + 1
    : ((exec.vu.idInTest - 1) % data.maxUserId) + 1;

  const joinRes = http.post(
    `${BASE_URL}/api/queue/join`,
    JSON.stringify({ policyId, userId }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'queue_join' } },
  );

  if (joinRes.status !== 200) {
    const code = errorCodeOf(joinRes);
    if (!countIfInfraFailure(code)) {
      if (code === 'ALREADY_ISSUED' || joinRes.status === 409) {
        joinDuplicate.add(1); // 이미 이 userId로 발급/대기 중 — userId가 겹칠 수 없는 구조에서
        // 이 값이 0이 아니면 이전 실행이 같은 정책에 남긴 흔적이거나 재시도 로직 문제를 의심할 것
      } else if (code === 'OUT_OF_STOCK') {
        joinSoldOut.add(1);
      } else if (code === 'QUEUE_FULL') {
        joinQueueFull.add(1);
      } else if (code === 'TOO_MANY_REQUESTS') {
        joinRateLimited.add(1);
      } else if (code === null && ((safeJson(joinRes.body) || {}).message || '').match(/소진|SOLD/)) {
        // errorCode가 아직 안 붙은 응답을 위한 fallback — 서버가 코드를 다 채우면 이 가지는
        // 도달하지 않는다(도달한다면 그 엔드포인트에 errorCode가 빠졌다는 신호).
        joinSoldOut.add(1);
      } else {
        joinOtherError.add(1);
      }
    }
    // VU가 실패를 무한 재시도하며 스핀하지 않도록 아주 짧게라도 쉰다.
    sleep(0.1);
    return;
  }

  let body = safeJson(joinRes.body) || {};
  const joinedAt = Date.now();
  let activeToken = body.activeToken;

  // WAITING이면 ADMITTED/SOLD_OUT/EXPIRED 중 하나로 끝날 때까지 서버가 알려준 재시도
  // 간격대로 폴링한다. QueueStatus enum(WAITING/ADMITTED/SOLD_OUT/EXPIRED)을 그대로 분기한다.
  const MAX_POLLS = 60;
  let attempts = 0;
  while (body.status === 'WAITING' && attempts < MAX_POLLS) {
    sleep(Math.max(0.2, body.retryAfterSeconds || 1));
    const statusRes = http.get(`${BASE_URL}/api/queue/status?policyId=${policyId}&userId=${userId}`, {
      tags: { name: 'queue_status' },
    });
    if (statusRes.status !== 200) {
      if (!countIfInfraFailure(errorCodeOf(statusRes))) {
        pollNon200.add(1);
      }
      sleep(0.1);
      return;
    }
    body = safeJson(statusRes.body) || {};
    activeToken = body.activeToken;
    attempts += 1;
  }

  if (body.status === 'SOLD_OUT') {
    pollSoldOut.add(1); // 대기 중 재고가 소진돼 품절로 종료됨
    return;
  }
  if (body.status === 'EXPIRED') {
    pollExpired.add(1); // 대기열 등록이 만료됨
    return;
  }
  if (body.status !== 'ADMITTED' || !activeToken) {
    pollTimeout.add(1); // MAX_POLLS를 넘도록 계속 WAITING — 대기열이 그만큼 밀렸다는 뜻
    return;
  }
  admitWaitMs.add(Date.now() - joinedAt);

  const issueRes = http.post(
    `${BASE_URL}/api/coupon-policies/${policyId}/issue`,
    JSON.stringify({ userId }),
    {
      headers: { 'Content-Type': 'application/json', 'X-Active-Token': activeToken },
      tags: { name: 'coupon_issue' },
    },
  );

  if (issueRes.status === 202) {
    issued.add(1);
    return;
  }

  const issueCode = errorCodeOf(issueRes);
  if (countIfInfraFailure(issueCode)) {
    sleep(0.1);
    return;
  }
  // ADMITTED까지 갔는데도 재고 소진(OUT_OF_STOCK) 또는 중복 발급(ALREADY_ISSUED)
  if (issueCode === 'ALREADY_ISSUED') {
    issueDuplicated.add(1);
  } else if (issueCode === 'OUT_OF_STOCK') {
    issueSoldOut.add(1);
  } else if (issueCode === null && (issueRes.body || '').match(/DUPLICATE|중복/)) {
    issueDuplicated.add(1); // errorCode가 아직 안 붙은 응답을 위한 fallback(위 join과 동일)
  } else if (issueCode === null && issueRes.status === 409) {
    issueSoldOut.add(1);
  } else {
    issueOtherError.add(1);
  }
  sleep(0.1); // 재고 소진 이후에도 VU가 쉬지 않고 계속 실패 요청만 반복하지 않도록
}
