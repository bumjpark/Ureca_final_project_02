package com.ureca.myureca.consumer;

/**
 * Kafka Consumer 처리 실패 원인 열거형.
 *
 * <p>LoggingFailureRecoverer에서 구조화된 로그를 기록할 때 사용하며,
 * 다음 이슈(DLT 전송)에서 DltPublishingFailureRecoverer로 교체해도 이 enum 그대로 재사용한다.
 */
public enum FailureReason {
    /** DB 커넥션 오류, 트랜잭션 타임아웃 등 일시적 인프라 장애 */
    DB_CONNECTION_ERROR,
    /** UNIQUE 제약 위반 등 DataIntegrityViolationException — 정상적인 2차 멱등성 방어 */
    CONSTRAINT_VIOLATION,
    /** Kafka 메시지 역직렬화 실패 (JSON 파싱 오류 등) */
    DESERIALIZATION_ERROR,
    /** 위 분류에 해당하지 않는 예상치 못한 예외 */
    UNKNOWN_ERROR;

    /**
     * 예외 타입을 분석하여 적절한 FailureReason으로 매핑한다.
     *
     * @param ex 발생한 예외
     * @return 매핑된 FailureReason
     */
    public static FailureReason from(Throwable ex) {
        if (ex == null) {
            return UNKNOWN_ERROR;
        }
        String className = ex.getClass().getName();
        // DataIntegrityViolationException 계열
        if (className.contains("DataIntegrityViolation")
                || className.contains("ConstraintViolation")
                || className.contains("DuplicateKey")) {
            return CONSTRAINT_VIOLATION;
        }
        // DB 커넥션/트랜잭션 계열
        if (className.contains("CannotAcquireLock")
                || className.contains("TransactionTimedOut")
                || className.contains("JdbcConnectionFailure")
                || className.contains("DataAccessResourceFailure")
                || className.contains("UnableToAcquireLock")) {
            return DB_CONNECTION_ERROR;
        }
        // Kafka 역직렬화 계열
        if (className.contains("Deserialization")
                || className.contains("JsonParseException")
                || className.contains("InvalidFormatException")
                || className.contains("SerializationException")) {
            return DESERIALIZATION_ERROR;
        }
        // cause까지 한 단계 더 확인
        if (ex.getCause() != null) {
            return from(ex.getCause());
        }
        return UNKNOWN_ERROR;
    }
}
