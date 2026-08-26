package com.ureca.myureca.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * POST /api/queue/join 요청 바디.
 */
public record QueueJoinRequest(

        @NotNull
        @Positive
        Long policyId,

        @NotNull
        @Positive
        Long userId
) {
}
