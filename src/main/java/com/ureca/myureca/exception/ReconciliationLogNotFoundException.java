package com.ureca.myureca.exception;

public class ReconciliationLogNotFoundException extends RuntimeException {

    private final Long logId;

    public ReconciliationLogNotFoundException(Long logId) {
        super("존재하지 않는 재처리 로그입니다. logId=" + logId);
        this.logId = logId;
    }

    public Long getLogId() {
        return logId;
    }
}
