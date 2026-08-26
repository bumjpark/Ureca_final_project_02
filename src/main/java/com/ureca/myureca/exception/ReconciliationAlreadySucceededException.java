package com.ureca.myureca.exception;

public class ReconciliationAlreadySucceededException extends RuntimeException {

    private final Long logId;

    public ReconciliationAlreadySucceededException(Long logId) {
        super("이미 성공한 재처리 건입니다. 재처리가 불필요합니다. logId=" + logId);
        this.logId = logId;
    }

    public Long getLogId() {
        return logId;
    }
}
