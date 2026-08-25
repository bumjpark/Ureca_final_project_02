package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import java.time.LocalDateTime;

/**
 * POST /api/admin/reconciliation/retry, GET /api/admin/reconciliation/logs 응답
 */
public record ReconciliationLogResponse(
        Long id,
        ReconciliationType type,
        ReconciliationStatus status,
        String eventKey,
        Long couponIssueId,
        String topic,
        Integer retryCount,
        String failReason,
        LocalDateTime processedAt,
        LocalDateTime createdAt
) {

    public static ReconciliationLogResponse from(ReconciliationLog log) {
        return new ReconciliationLogResponse(
                log.getId(),
                log.getType(),
                log.getStatus(),
                log.getEventKey(),
                log.getCouponIssue() != null ? log.getCouponIssue().getId() : null,
                log.getTopic(),
                log.getRetryCount(),
                log.getFailReason(),
                log.getProcessedAt(),
                log.getCreatedAt()
        );
    }
}
