package com.ureca.myureca.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/mock/notifications/kakao/bulk 요청 바디 — 정책 수신자 전원에게 발송. */
public record MockNotificationBulkRequest(
        @NotNull Long policyId,
        @NotBlank String templateId,
        @NotBlank String message
) {
}
