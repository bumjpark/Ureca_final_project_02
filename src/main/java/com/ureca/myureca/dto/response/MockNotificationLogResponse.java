package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.notification.MockNotificationLog;
import java.time.LocalDateTime;

public record MockNotificationLogResponse(
        Long id,
        Long userId,
        Long couponPolicyId,
        String templateId,
        String message,
        String status,
        String messageId,
        String failReason,
        LocalDateTime createdAt
) {
    public static MockNotificationLogResponse from(MockNotificationLog log) {
        return new MockNotificationLogResponse(
                log.getId(),
                log.getUserId(),
                log.getCouponPolicyId(),
                log.getTemplateId(),
                log.getMessage(),
                log.getStatus(),
                log.getMessageId(),
                log.getFailReason(),
                log.getCreatedAt()
        );
    }
}
