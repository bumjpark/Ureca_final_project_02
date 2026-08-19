package com.ureca.myureca.domain.reconciliation;

import com.ureca.myureca.domain.coupon.CouponIssue;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * reconciliation_log 테이블 매핑 엔티티.
 * Kafka 발행 실패건 재발행 / 상태 불일치 수동 재처리 / DLT 재처리 / Redis 완전 유실 복구를 위한
 * 재처리 큐 및 자가 치유 로그.
 */
@Entity
@Table(
        name = "reconciliation_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_reconciliation_event_key", columnNames = "event_key"),
        indexes = {
                @Index(name = "idx_reconciliation_issue", columnList = "coupon_issue_id"),
                @Index(name = "idx_reconciliation_status", columnList = "status, type")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "type",
            nullable = false,
            length = 20,
            check = @CheckConstraint(
                    name = "chk_reconciliation_type",
                    constraint = "type in ('EVENT_REPUBLISH','ISSUE_REPROCESS','DLT_REPROCESS','REDIS_RECOVER')"
            )
    )
    private ReconciliationType type;

    @Column(name = "event_key", length = 64)
    private String eventKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "coupon_issue_id",
            nullable = true,
            foreignKey = @ForeignKey(name = "fk_reconciliation_issue")
    )
    private CouponIssue couponIssue;

    @Column(name = "topic", length = 100)
    private String topic;

    /** 재발행할 원본 이벤트 JSON payload. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            check = @CheckConstraint(name = "chk_reconciliation_status", constraint = "status in ('PENDING','SUCCESS','FAILED')")
    )
    private ReconciliationStatus status;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "requested_by", length = 50)
    private String requestedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ReconciliationLog(
            ReconciliationType type,
            String eventKey,
            CouponIssue couponIssue,
            String topic,
            String payload,
            String requestedBy
    ) {
        this.type = type;
        this.eventKey = eventKey;
        this.couponIssue = couponIssue;
        this.topic = topic;
        this.payload = payload;
        this.requestedBy = requestedBy;
        this.status = ReconciliationStatus.PENDING;
        this.retryCount = 0;
    }

    public void increaseRetryCount() {
        this.retryCount++;
    }

    /** 재처리 성공 처리: PENDING/FAILED → SUCCESS. */
    public void markSuccess() {
        this.status = ReconciliationStatus.SUCCESS;
        this.processedAt = LocalDateTime.now();
    }

    /** 재처리 실패 처리: PENDING → FAILED. */
    public void markFailed(String failReason) {
        this.status = ReconciliationStatus.FAILED;
        this.failReason = failReason;
        this.processedAt = LocalDateTime.now();
    }
}
