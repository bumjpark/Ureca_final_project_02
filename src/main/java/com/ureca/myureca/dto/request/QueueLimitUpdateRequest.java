package com.ureca.myureca.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * PATCH /api/admin/queue/limit 요청 바디.
 *
 * <ul>
 *   <li>{@code policyId} – 특정 쿠폰 정책 ID (선택 사항, null인 경우 글로벌 기본 Limit 변경)</li>
 *   <li>{@code limit}    – 초당 통과 인원수 (1 ~ 50,000 범위)</li>
 * </ul>
 */
public record QueueLimitUpdateRequest(

        @Positive
        Long policyId,

        @NotNull
        @Min(1)
        @Max(50000)
        Integer limit
) {
}
