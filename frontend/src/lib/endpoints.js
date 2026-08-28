import { api, apiJson, newIdempotencyKey } from './api.js';

/* ────────────────────────────────────────────────────────────
   백엔드에 실제로 구현된 엔드포인트만 감싼다.
   (컨트롤러: CouponPolicyAdminController, CouponStatusController,
    QueueController, CouponIssueController, MyCouponController,
    CouponDetailController, CouponHistoryController, CouponUseController,
    VerificationController, ReconciliationController, HealthController)
   ──────────────────────────────────────────────────────────── */

// ── 쿠폰 정책 ────────────────────────────────────────────────
export const listPolicies = (page = 0, size = 50) =>
  apiJson(`/api/admin/coupon-policies?page=${page}&size=${size}`);

export const getPolicy = (policyId) =>
  apiJson(`/api/admin/coupon-policies/${policyId}`);

// 정책 생성 — 201 Created. 교차검증(closeAt<openAt, RATE 할인율 1~100)은 서버가 400으로 응답
export const createPolicy = (body) =>
  apiJson('/api/admin/coupon-policies', { method: 'POST', body });

// 정책 수정 — 오픈 시각 이후에는 서버가 400으로 거부. 모든 필드 필수(전체 교체)
export const updatePolicy = (policyId, body) =>
  apiJson(`/api/admin/coupon-policies/${policyId}`, { method: 'PATCH', body });

// 정책 삭제(소프트 삭제) — 204. Redis 키 삭제가 선행됨
export const deletePolicy = (policyId) =>
  api(`/api/admin/coupon-policies/${policyId}`, { method: 'DELETE' });

// ── 발급 현황(재고) : DB 정책 + Redis 실시간 재고 ───────────────
export const getCouponStatus = (policyId) =>
  apiJson(`/api/coupon-policies/${policyId}/status`);

// ── 대기열 ──────────────────────────────────────────────────
export const joinQueue = (policyId, userId) =>
  apiJson('/api/queue/join', { method: 'POST', body: { policyId, userId } });

export const getQueueStatus = (policyId, userId) =>
  apiJson(`/api/queue/status?policyId=${policyId}&userId=${userId}`);

// ── 발급 (202 Accepted 비동기) ──────────────────────────────
export async function issueCoupon(policyId, userId, activeToken) {
  const { data, status } = await api(`/api/coupon-policies/${policyId}/issue`, {
    method: 'POST',
    body: { userId },
    headers: activeToken ? { 'X-Active-Token': activeToken } : {},
  });
  return { ...data, httpStatus: status }; // { status:'ACCEPTED', receiptId, message }
}

// ── 내 쿠폰함 ────────────────────────────────────────────────
export function getMyCoupons(userId, { status, couponPolicyId, page = 0, size = 100 } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) qs.set('status', status);
  if (couponPolicyId != null) qs.set('couponPolicyId', String(couponPolicyId));
  return apiJson(`/api/users/${userId}/coupons?${qs.toString()}`);
}

export const getCouponDetail = (couponIssueId, userId) =>
  apiJson(`/api/coupons/${couponIssueId}?userId=${userId}`);

export const getCouponHistory = (couponIssueId) =>
  apiJson(`/api/coupons/${couponIssueId}/history`);

// ── 쿠폰 상태 변경 (멱등성: Idempotency-Key 헤더) ──────────────
// status: 'USED'(사용) | 'ISSUED'(사용 취소) | 'EXPIRED'(만료)
export function changeCouponStatus(couponIssueId, { userId, status, reason }, idempotencyKey) {
  return apiJson(`/api/coupons/${couponIssueId}/use`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey ?? newIdempotencyKey() },
    body: { userId, status, reason },
  });
}

// ── 정합성 검증 ─────────────────────────────────────────────
export async function runVerification({ policyId, force = false } = {}) {
  const qs = new URLSearchParams();
  if (policyId != null) qs.set('policyId', String(policyId));
  if (force) qs.set('force', 'true');
  const { data, status } = await api(`/api/admin/verification/run?${qs.toString()}`, {
    method: 'POST',
  });
  return { data, status };
}

export function listVerificationReports({ policyId, status, page = 0, size = 20 } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) });
  if (policyId != null) qs.set('policyId', String(policyId));
  if (status) qs.set('status', status);
  return apiJson(`/api/admin/verification/reports?${qs.toString()}`);
}

export const getVerificationReport = (id) =>
  apiJson(`/api/admin/verification/reports/${id}`);

export const verificationReportCsvUrl = (id) =>
  `/api/admin/verification/reports/${id}?format=csv`;

// 불일치 상세 CSV 원본 텍스트 (검증 항목별 건수 집계에 사용)
export async function getVerificationReportCsv(id) {
  const res = await fetch(verificationReportCsvUrl(id), { headers: { Accept: 'text/csv' } });
  if (!res.ok) return null;
  return res.text();
}

// ── 정합성 복구(재처리 큐) 로그 ──────────────────────────────
export function listReconciliationLogs({ type, status, page = 0, size = 20 } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) });
  if (type) qs.set('type', type);
  if (status) qs.set('status', status);
  return apiJson(`/api/admin/reconciliation/logs?${qs.toString()}`);
}

// ── 인프라 헬스체크 (deep = DB/Redis/Kafka 병렬 점검) ─────────
export async function getHealth(deep = true) {
  try {
    const { data } = await api(`/api/health?deep=${deep}`);
    return data;
  } catch (e) {
    // deep 체크는 하나라도 DOWN이면 503 + 바디를 준다
    if (e.body && e.body.components) return e.body;
    return { status: 'DOWN', components: {}, checkedAt: null };
  }
}
