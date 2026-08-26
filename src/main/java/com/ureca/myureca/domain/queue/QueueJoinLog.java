package com.ureca.myureca.domain.queue;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * queue_join_log 테이블 매핑 엔티티. 대기열 진입 순서 영속화(FCFS 검증 근거 데이터).
 */
@Entity
@Table(
        name = "queue_join_log",
        indexes = @Index(name = "idx_policy_rank", columnList = "coupon_policy_id, queue_rank")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QueueJoinLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_policy_id", nullable = false)
    private Long couponPolicyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            check = @CheckConstraint(name = "chk_queue_join_log_status", constraint = "status in ('WAITING','ADMITTED')")
    )
    private QueueStatus status;

    /** join_queue.lua seq(INCR) 기반 절대 선착순 번호표. 전역 순서 판단 기준 — ZRANK 아님. */
    @Column(name = "queue_rank", nullable = false)
    private Long queueRank;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public QueueJoinLog(Long couponPolicyId, Long userId, QueueStatus status, Long queueRank, LocalDateTime joinedAt) {
        this.couponPolicyId = couponPolicyId;
        this.userId = userId;
        this.status = status;
        this.queueRank = queueRank;
        this.joinedAt = joinedAt;
    }
}
