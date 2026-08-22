package com.ureca.myureca.exception;

/**
 * 대기열 정원(maxQueueSize)을 초과한 경우 발생.
 *
 * <p>503 SERVICE_UNAVAILABLE: 서버가 일시적으로 요청을 처리할 수 없는 상태임을 클라이언트에 알린다.
 */
public class QueueFullException extends RuntimeException {

    public QueueFullException(Long policyId) {
        super("대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요. policyId=" + policyId);
    }
}
