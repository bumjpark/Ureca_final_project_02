import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

// Custom Metrics
const couponsIssued = new Counter('coupons_issued_total');
const couponsSoldOut = new Counter('coupons_sold_out_total');
const couponsFailed = new Counter('coupons_failed_total');
const e2ePipelineDuration = new Trend('e2e_pipeline_duration_ms', true);
const issueSuccessRate = new Rate('issue_success_rate');

export const options = {
  scenarios: {
    full_e2e_scale: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      preAllocatedVUs: 400,
      maxVUs: 1500,
      stages: [
        { target: 400, duration: '10s' }, // 10초까지 400 RPS로 증가
        { target: 600, duration: '20s' }, // 30초까지 600 RPS로 증가 (완판 유도)
        { target: 800, duration: '15s' }, // 45초까지 800 RPS로 완판 후 차단 확인
      ],
      gracefulStop: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    issue_success_rate: ['rate>0.85'],
  },
};

const BASE_URL = 'http://localhost:8080';
const POLICY_ID = 3; // 2만 VU 실전 테스트 쿠폰 (재고 10,000장 완판 테스트)

export default function () {
  const uniqueIdx = exec.scenario.iterationInTest;
  const userId = 1000 + (uniqueIdx % 23000); // 1000 ~ 24000 범위의 DB 실존 고유 유저
  const start = Date.now();

  // 1. 대기열 등록 (POST /api/queue/join)
  const joinPayload = JSON.stringify({ policyId: POLICY_ID, userId: userId });
  const joinRes = http.post(`${BASE_URL}/api/queue/join`, joinPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  let activeToken = null;

  if (joinRes.status === 200) {
    try {
      const joinBody = JSON.parse(joinRes.body);
      if (joinBody.status === 'ADMITTED') {
        activeToken = joinBody.activeToken;
      }
    } catch (e) {}
  } else if (joinRes.status === 400) {
    // 이미 1만장 소진 후 마감
    couponsSoldOut.add(1);
    return;
  }

  // 2. WAITING 상태인 경우 상태 폴링 (스케줄러가 매초 수천명씩 통과시킴)
  let attempts = 0;
  while (!activeToken && attempts < 15) {
    sleep(0.3); // 300ms 주기로 순번 체크
    attempts++;

    const statusRes = http.get(`${BASE_URL}/api/queue/status?policyId=${POLICY_ID}&userId=${userId}`);
    if (statusRes.status === 200) {
      try {
        const statusBody = JSON.parse(statusRes.body);
        if (statusBody.status === 'ADMITTED') {
          activeToken = statusBody.activeToken;
          break;
        }
        if (statusBody.status === 'SOLD_OUT') {
          couponsSoldOut.add(1);
          return;
        }
      } catch (e) {}
    } else if (statusRes.status === 400) {
      couponsSoldOut.add(1);
      return;
    }
  }

  if (!activeToken) {
    couponsFailed.add(1);
    issueSuccessRate.add(false);
    return;
  }

  // 3. 실제 쿠폰 발급 (POST /api/coupon-policies/{policyId}/issue)
  const issuePayload = JSON.stringify({ userId: userId });
  const issueRes = http.post(`${BASE_URL}/api/coupon-policies/${POLICY_ID}/issue`, issuePayload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Active-Token': activeToken,
    },
  });

  const isIssued = check(issueRes, {
    'issue accepted (202)': (r) => r.status === 202,
  });

  if (isIssued) {
    couponsIssued.add(1);
    issueSuccessRate.add(true);
    e2ePipelineDuration.add(Date.now() - start);
  } else {
    if (issueRes.status === 400) {
      couponsSoldOut.add(1);
    } else {
      couponsFailed.add(1);
    }
    issueSuccessRate.add(false);
  }
}
