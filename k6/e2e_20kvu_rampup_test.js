import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// ==========================================
// 커스텀 지표
// ==========================================
const couponsIssued = new Counter('coupons_issued_count');
const couponsSoldOut = new Counter('coupons_sold_out_count');
const queueWaiting = new Counter('queue_waiting_count');
const pipelineDuration = new Trend('e2e_pipeline_duration_ms', true);
const unexpectedErrors = new Counter('unexpected_errors');

export const options = {
  scenarios: {
    vu_20k_rampup_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10000 }, // 30초간 10,000 VU로 상승
        { duration: '30s', target: 20000 }, // 60초까지 20,000 VU(최대치) 도달
        { duration: '15s', target: 20000 }, // 15초간 20,000 VU 풀 유지 (완판 유도)
        { duration: '10s', target: 0 },     // 10초간 쿨다운
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 1만장 이후 400 SOLD_OUT 차단은 정상 동작이므로 에러 한도 완화
    http_req_failed: ['rate<0.60'],
  },
  discardResponseBodies: false,
};

const BASE_URL = __ENV.TARGET_HOST || 'http://localhost:8080';
const POLICY_ID = parseInt(__ENV.POLICY_ID || '1'); // 기본값 1번 정책

const commonHeaders = {
  'Content-Type': 'application/json',
  'Connection': 'keep-alive',
};

export default function () {
  // 1. 20,000명의 고유 유저 매핑 (1 VU = 1명의 유저 ID)
  if (__ITER > 0) {
    sleep(1);
    return;
  }
  const userId = __VU;
  const start = Date.now();

  // 2. 대기열 등록 (POST /api/queue/join) - 네트워크 지연 시 최대 15회 재시도 (실제 클라이언트 재시도 모사)
  const joinPayload = JSON.stringify({ policyId: POLICY_ID, userId: userId });
  let joinRes = null;
  let activeToken = null;

  for (let attempt = 0; attempt < 15; attempt++) {
    joinRes = http.post(`${BASE_URL}/api/queue/join`, joinPayload, {
      headers: commonHeaders,
      timeout: '10s',
    });

    if (joinRes && joinRes.status === 200) {
      try {
        const joinBody = JSON.parse(joinRes.body);
        if (joinBody.status === 'ADMITTED') {
          activeToken = joinBody.activeToken;
        } else if (joinBody.status === 'WAITING') {
          queueWaiting.add(1);
        }
      } catch (e) {}
      break;
    }

    if (joinRes && joinRes.status === 400) {
      // 10,000장 완판 후 마감 (Fast-Fail)
      couponsSoldOut.add(1);
      return;
    }
    if (joinRes && joinRes.status === 409) {
      // 이미 발급/참여 완료된 유저
      return;
    }
    if (joinRes && joinRes.status === 503) {
      // 대기열 일시적 포화: 잠시 대기 후 재시도
      sleep(1);
      continue;
    }

    // 일시적 네트워크 소켓/연결 오류: 1초 대기 후 재시도
    sleep(1);
  }

  if (!joinRes || joinRes.status !== 200) {
    unexpectedErrors.add(1);
    return;
  }

  // 3. 대기열 상태 폴링 (동적 백오프 적용, 최대 60회 시도)
  let attempts = 0;
  let nextWaitSec = 1.0;
  while (!activeToken && attempts < 60) {
    sleep(nextWaitSec);
    attempts++;

    const statusRes = http.get(
      `${BASE_URL}/api/queue/status?policyId=${POLICY_ID}&userId=${userId}`,
      { headers: commonHeaders, timeout: '10s' }
    );

    if (statusRes.status === 200) {
      try {
        const statusBody = JSON.parse(statusRes.body);
        if (statusBody.status === 'ADMITTED' && statusBody.activeToken) {
          activeToken = statusBody.activeToken;
          break;
        }
        if (statusBody.status === 'SOLD_OUT') {
          couponsSoldOut.add(1);
          return;
        }
        if (statusBody.status === 'NOT_FOUND') {
          return;
        }

        // 서버 제공 retryAfterSeconds 기반 동적 백오프
        nextWaitSec = (statusBody.retryAfterSeconds && statusBody.retryAfterSeconds > 0)
          ? Math.max(0.5, statusBody.retryAfterSeconds)
          : 1.0;
      } catch (e) {
        unexpectedErrors.add(1);
      }
    } else if (statusRes.status === 400) {
      couponsSoldOut.add(1);
      return;
    } else if (statusRes.status === 404 || statusRes.status === 409) {
      return;
    }
  }

  // 토큰을 못 받았으면 종료
  if (!activeToken) {
    return;
  }

  // 4. 실제 쿠폰 발급 (POST /api/coupon-policies/{policyId}/issue)
  const issuePayload = JSON.stringify({ userId: userId });
  let issueRes = null;

  for (let attempt = 0; attempt < 5; attempt++) {
    issueRes = http.post(
      `${BASE_URL}/api/coupon-policies/${POLICY_ID}/issue`,
      issuePayload,
      {
        headers: {
          'Content-Type': 'application/json',
          'Connection': 'keep-alive',
          'X-Active-Token': activeToken,
        },
        timeout: '10s',
      }
    );

    if (issueRes && (issueRes.status === 202 || issueRes.status === 400 || issueRes.status === 409)) {
      break;
    }
    sleep(1);
  }

  if (issueRes && issueRes.status === 202) {
    couponsIssued.add(1);
    pipelineDuration.add(Date.now() - start);
  } else if (issueRes && issueRes.status === 400) {
    couponsSoldOut.add(1);
  } else {
    unexpectedErrors.add(1);
  }

  sleep(0.5);
}
