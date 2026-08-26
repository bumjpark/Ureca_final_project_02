package com.ureca.myureca.exception;

import java.util.List;

/**
 * 전체 재처리(logId 생략) 중 예기치 못한 예외로 중단된 경우
 */
public class ReconciliationBulkDispatchException extends RuntimeException {

    private final List<Long> dispatchedLogIds;

    public ReconciliationBulkDispatchException(String message, List<Long> dispatchedLogIds, Throwable cause) {
        super(message, cause);
        this.dispatchedLogIds = dispatchedLogIds;
    }

    public List<Long> getDispatchedLogIds() {
        return dispatchedLogIds;
    }
}
