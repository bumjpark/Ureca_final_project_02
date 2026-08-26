package com.ureca.myureca.exception;

import java.util.List;

/**
 * 여러 정책을 순회하며 검증을 접수하는 도중 실패했을 때 발생.
 */
public class VerificationDispatchException extends RuntimeException {

    private final List<Long> dispatchedPolicyIds;

    public VerificationDispatchException(String message, List<Long> dispatchedPolicyIds, Throwable cause) {
        super(message, cause);
        this.dispatchedPolicyIds = dispatchedPolicyIds;
    }

    public List<Long> getDispatchedPolicyIds() {
        return dispatchedPolicyIds;
    }
}
