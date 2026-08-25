package com.ureca.myureca.exception;

public class QueueNotRegisteredException extends RuntimeException {

    public QueueNotRegisteredException(Long policyId, Long userId) {
        super(String.format("대기열 등록 이력이 존재하지 않습니다. policyId=%d, userId=%d", policyId, userId));
    }
}
