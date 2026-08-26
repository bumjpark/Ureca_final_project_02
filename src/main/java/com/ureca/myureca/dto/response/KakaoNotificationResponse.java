package com.ureca.myureca.dto.response;

import java.time.LocalDateTime;

public record KakaoNotificationResponse(
        String status,
        String messageId,
        LocalDateTime sentAt
) {

    public static KakaoNotificationResponse sent(String messageId) {
        return new KakaoNotificationResponse("SENT", messageId, LocalDateTime.now());
    }

    public static KakaoNotificationResponse failed(String messageId) {
        return new KakaoNotificationResponse("FAILED", messageId, LocalDateTime.now());
    }
}
