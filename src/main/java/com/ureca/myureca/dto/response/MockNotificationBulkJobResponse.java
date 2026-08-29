package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.notification.MockNotificationBulkJob;
import java.time.LocalDateTime;

/** 정책별 일괄 발송 진행 상태 1건. */
public record MockNotificationBulkJobResponse(
        Long id,
        Long policyId,
        String templateId,
        String message,
        int targetCount,
        int sentCount,
        int failedCount,
        String status,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {
    public static MockNotificationBulkJobResponse from(MockNotificationBulkJob job) {
        return new MockNotificationBulkJobResponse(
                job.getId(),
                job.getCouponPolicyId(),
                job.getTemplateId(),
                job.getMessage(),
                job.getTargetCount(),
                job.getSentCount(),
                job.getFailedCount(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }
}
