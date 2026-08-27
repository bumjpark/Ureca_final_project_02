package com.ureca.myureca.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 실제 카카오 알림톡 발송 API 요청을 흉내 낸 Mock 요청 스키마.
 * 이 프로젝트는 외부 연동을 Mocking 처리해야 해서(FR-5), 진짜 카카오 API 대신 이걸 부른다.
 */
public record KakaoNotificationRequest(
        @NotNull Long userId,
        @NotBlank String templateId,
        @NotBlank String message
) {
}
