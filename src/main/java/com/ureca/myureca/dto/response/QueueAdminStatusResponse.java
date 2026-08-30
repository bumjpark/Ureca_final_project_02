package com.ureca.myureca.dto.response;

/**
 * GET /api/admin/queue/status 응답 DTO. "지금 대기열이 줄어들고 있는지" 눈으로 확인하기 위한
 * 실시간 조회용 — {@link QueueLimitResponse}(쓰기 결과)와 달리 순수 조회 전용이다.
 */
public record QueueAdminStatusResponse(
        Long policyId,
        long waitingCount,
        int currentLimit,
        boolean usingDefaultLimit
) {
}
