import { api, apiJson, newIdempotencyKey } from './api.js';

/* ────────────────────────────────────────────────────────────
   백엔드에 실제로 구현된 엔드포인트만 감싼다. 컨트롤러 소스로 계약을 직접 확인했다:
   UserController, CouponPolicyController(생성), CouponPolicyAdminController(목록/상세/수정/삭제),
   CouponStatusController, QueueController, QueueAdminController, CouponIssueController,
   MyCouponController, CouponDetailController, CouponHistoryController, CouponUseController,
   VerificationController, ReconciliationController, MockNotificationController,
   RedisRecoveryController, HealthController.
   ──────────────────────────────────────────────────────────── */

// ── 유저 검색 (100만 건 규모라 검색어 없이는 호출하지 않을 것) ──
export function searchUsers(search, page = 0, size = 20) {
  const qs = new URLSearchParams({ search, page: String(page), size: String(size) });
  return apiJson(`/api/users?${qs.toString()}`);
}

// ── 쿠폰 정책 ────────────────────────────────────────────────
// sort는 Spring Pageable 표준 형식("id,desc" 등). 서버가 Pageable을 그대로 받으므로
// 별도 백엔드 변경 없이 정렬이 적용된다. 안 넘기면 기존처럼 DB 기본 순서(id 오름차순).
export const listPolicies = (page = 0, size = 50, sort) =>
  apiJson(
    `/api/admin/coupon-policies?page=${page}&size=${size}` +
      (sort ? `&sort=${encodeURIComponent(sort)}` : ''),
  );

export const getPolicy = (policyId) =>
  apiJson(`/api/admin/coupon-policies/${policyId}`);

// 정책 생성 — 201 Created. 교차검증(closeAt<openAt, RATE 할인율 1~100)은 서버가 400으로 응답
export const createPolicy = (body) =>
  apiJson('/api/admin/coupon-policies', { method: 'POST', body });

// 정책 수정 — 오픈 시각 이후에는 서버가 400으로 거부. 모든 필드 필수(전체 교체)
export const updatePolicy = (policyId, body) =>
  apiJson(`/api/admin/coupon-policies/${policyId}`, { method: 'PATCH', body });

// 정책 삭제(소프트 삭제) — 204
export const deletePolicy = (policyId) =>
  api(`/api/admin/coupon-policies/${policyId}`, { method: 'DELETE' });

// ── 발급 현황(재고) : DB 정책 + Redis 실시간 재고 ───────────────
export const getCouponStatus = (policyId) =>
  apiJson(`/api/coupon-policies/${policyId}/status`);

// 실시간 발급 그래프(1초 버킷) + 사용/만료 건수, 초당 발급 속도
export const getCouponIssuanceMetrics = (policyId, seconds = 60) =>
  apiJson(`/api/coupon-policies/${policyId}/status/metrics?seconds=${seconds}`);

// ── 대기열 ──────────────────────────────────────────────────
export const joinQueue = (policyId, userId) =>
  apiJson('/api/queue/join', { method: 'POST', body: { policyId, userId } });

export const getQueueStatus = (policyId, userId) =>
  apiJson(`/api/queue/status?policyId=${policyId}&userId=${userId}`);

// 대기열 처리 Limit(초당 통과 정원) 동적 조정 — policyId 없으면 글로벌 기본값
export const updateQueueLimit = ({ policyId, limit }) =>
  apiJson('/api/admin/queue/limit', { method: 'PATCH', body: { policyId, limit } });

// 지금 이 정책 대기열에 몇 명이 대기 중인지 + 적용 중인 처리 속도 (부하테스트 중 대기열이
// 실제로 줄어드는지 확인하는 용도)
export const getAdminQueueStatus = (policyId) =>
  apiJson(`/api/admin/queue/status?policyId=${policyId}`);

// ── 발급 (202 Accepted 비동기) ──────────────────────────────
export async function issueCoupon(policyId, userId, activeToken) {
  const { data, status } = await api(`/api/coupon-policies/${policyId}/issue`, {
    method: 'POST',
    body: { userId },
    headers: activeToken ? { 'X-Active-Token': activeToken } : {},
  });
  return { ...data, httpStatus: status }; // { status:'ACCEPTED', receiptId, message }
}

// 202로 받은 접수증(receiptId)의 처리 결과를 조회한다. 발급은 Kafka 비동기라 202 직후에는
// 아직 DB에 없을 수 있는데, 이 API가 그 상태를 "존재하지 않음"과 구분해서 알려준다.
//   ISSUED  — DB 반영 완료. coupon에 상세가 들어있다
//   PENDING — 아직 반영 전(정상적인 비동기 지연일 수 있음). 잠시 후 다시 조회
//   FAILED  — 발행/소비가 실패해 재처리 대기 중. note에 사유
export const getIssueStatus = (receiptId) =>
  apiJson(`/api/coupons/receipt/${encodeURIComponent(receiptId)}`);

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

// CSV를 내려받지 않아도 어떤 불일치가 발견됐는지 화면에서 바로 보기 위한 조회 —
// 리포트가 만들어둔 CSV를 그대로 파싱해서 페이지 단위로 준다(status가 MISMATCH_FOUND일 때만 호출할 것)
export const getVerificationMismatches = (reportId, page = 0, size = 20) =>
  apiJson(`/api/admin/verification/reports/${reportId}/mismatches?page=${page}&size=${size}`);

// ── 정합성 복구(재처리 큐) 로그 ──────────────────────────────
export function listReconciliationLogs({ policyId, type, status, page = 0, size = 20 } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) });
  if (policyId != null) qs.set('policyId', String(policyId));
  if (type) qs.set('type', type);
  if (status) qs.set('status', status);
  return apiJson(`/api/admin/reconciliation/logs?${qs.toString()}`);
}

// 단건 재처리(logId 지정) 또는 전체 재처리(type만 지정, 기본 EVENT_REPUBLISH)
export function retryReconciliation({ logId, type } = {}) {
  const qs = new URLSearchParams();
  if (logId != null) qs.set('logId', String(logId));
  if (type) qs.set('type', type);
  return apiJson(`/api/admin/reconciliation/retry?${qs.toString()}`, { method: 'POST' });
}

// ── Redis 완전 유실 복구 ─────────────────────────────────────
export const recoverRedis = (eventId) =>
  apiJson(`/api/coupons/${eventId}/recover`, { method: 'POST' });

// ── Mock 카카오 알림톡 발송 ──────────────────────────────────
export const sendMockKakao = ({ userId, templateId, message }, simulateFailure = false) =>
  apiJson(`/api/mock/notifications/kakao?simulateFailure=${simulateFailure}`, {
    method: 'POST',
    body: { userId, templateId, message },
  });

// 정책 수신자 전원에게 일괄 발송 — 즉시 {jobId, policyId, targetCount}만 반환, 실제 발송은
// 서버가 비동기로 계속한다. 건별 결과는 listMockNotificationLogs, 진행 상태는
// listMockNotificationBulkJobs로 확인.
export const sendMockKakaoBulk = ({ policyId, templateId, message }) =>
  apiJson('/api/mock/notifications/kakao/bulk', { method: 'POST', body: { policyId, templateId, message } });

// 정책별 일괄 발송이 진행 중인지/끝났는지, 대상자 중 몇 명이 끝났는지(성공/실패)를 보여준다.
export function listMockNotificationBulkJobs({ policyId, page = 0, size = 20 } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) });
  if (policyId != null) qs.set('policyId', String(policyId));
  return apiJson(`/api/mock/notifications/bulk-jobs?${qs.toString()}`);
}

export function listMockNotificationLogs({ policyId, page = 0, size = 20 } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) });
  if (policyId != null) qs.set('policyId', String(policyId));
  return apiJson(`/api/mock/notifications/logs?${qs.toString()}`);
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
