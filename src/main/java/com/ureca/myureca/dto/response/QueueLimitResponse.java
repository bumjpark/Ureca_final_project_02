package com.ureca.myureca.dto.response;

import java.time.LocalDateTime;

/**
 * PATCH /api/admin/queue/limit 응답 DTO.
 */
public record QueueLimitResponse(
        Long policyId,
        int limit,
        LocalDateTime updatedAt
) {

    public static QueueLimitResponse of(Long policyId, int limit) {
        return new QueueLimitResponse(policyId, limit, LocalDateTime.now());
    }
}
