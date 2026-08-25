package com.ureca.myureca.exception;

import com.ureca.myureca.domain.reconciliation.ReconciliationType;

/**
 * 재처리 API는 EVENT_REPUBLISH만 실제로 처리한다(ISSUE_REPROCESS/DLT_REPROCESS는 이를 처리할
 * Consumer/DLT 인프라가 아직 없고, REDIS_RECOVER는 별도 엔드포인트로 분리됨)
 */
public class ReconciliationTypeNotSupportedException extends RuntimeException {

    private final Long logId;
    private final ReconciliationType type;

    public ReconciliationTypeNotSupportedException(Long logId, ReconciliationType type) {
        super("EVENT_REPUBLISH 타입만 재처리를 지원합니다. logId=" + logId + ", type=" + type);
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
