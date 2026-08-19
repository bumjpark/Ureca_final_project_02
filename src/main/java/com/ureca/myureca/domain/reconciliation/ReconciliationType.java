package com.ureca.myureca.domain.reconciliation;

/**
 * reconciliation_log.type 값 도메인. DB CHECK 제약: chk_reconciliation_type.
 */
public enum ReconciliationType {
    /** Kafka 발행 실패건 재발행 */
    EVENT_REPUBLISH,
    /** 상태 불일치 수동 재처리 */
    ISSUE_REPROCESS,
    /** DLT(Dead Letter Topic) 재처리 */
    DLT_REPROCESS,
    /** Redis 완전 유실 복구 */
    REDIS_RECOVER
}
