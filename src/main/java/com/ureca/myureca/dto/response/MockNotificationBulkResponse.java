package com.ureca.myureca.dto.response;

/**
 * POST /api/mock/notifications/kakao/bulk 응답.
 * 실제 발송은 비동기로 흘려보내므로(대상자가 많으면 오래 걸릴 수 있음), 여기서는
 * "몇 명에게 발송을 접수했는지"와 진행 상태를 조회할 jobId만 즉시 돌려준다.
 * 진행 상황은 GET .../bulk-jobs?policyId=로, 건별 결과는 GET .../logs?policyId=로 확인한다.
 */
public record MockNotificationBulkResponse(Long jobId, Long policyId, int targetCount) {
}
