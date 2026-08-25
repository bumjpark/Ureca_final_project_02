package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import java.time.LocalDateTime;

/**
 * POST /api/admin/reconciliation/retry 응답
 */
public record ReconciliationLogResponse(
        Long id,
        ReconciliationType type,
        ReconciliationStatus status,
        String eventKey,
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
                log.getTopic(),
                log.getRetryCount(),
                log.getFailReason(),
                log.getProcessedAt(),
                log.getCreatedAt()
        );
    }
}
