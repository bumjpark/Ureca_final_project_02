package com.ureca.myureca.exception;

import com.ureca.myureca.domain.reconciliation.ReconciliationType;

/**
 * 재처리 API는 EVENT_REPUBLISH·DLT_REPROCESS만 실제로 처리한다
 * (ISSUE_REPROCESS는 아직 처리할 인프라가 없고, REDIS_RECOVER는 별도 엔드포인트로 분리됨. UBM-37)
 */
public class ReconciliationTypeNotSupportedException extends RuntimeException {

    private final Long logId;
    private final ReconciliationType type;

    public ReconciliationTypeNotSupportedException(Long logId, ReconciliationType type) {
        super("EVENT_REPUBLISH 또는 DLT_REPROCESS 타입만 재처리를 지원합니다. logId=" + logId + ", type=" + type);
        this.logId = logId;
        this.type = type;
    }

    public Long getLogId() {
        return logId;
    }

    public ReconciliationType getType() {
        return type;
    }
}
