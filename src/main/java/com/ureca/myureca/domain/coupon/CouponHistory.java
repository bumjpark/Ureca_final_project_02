package com.ureca.myureca.domain.coupon;

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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * coupon_history 테이블 매핑 엔티티. 불변(Append-Only) 감사 로그.
 *
 * <p>request_id는 클라이언트 멱등성 키로, UNIQUE 제약(uk_history_request)을 통해
 * 동일 요청의 중복 반영을 DB 레벨에서 차단한다.
 *
 * <p>prevStatus는 IssueStatus가 아닌 HistoryPrevStatus를 사용한다.
 * 최초 발급 시 이전 상태가 없음을 NONE으로 표현해야 하며,
 * coupon_issue.status에는 NONE 개념이 존재하지 않으므로 별도 enum으로 분리한다.
 */
@Entity
@Table(
        name = "coupon_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_history_request", columnNames = "request_id"),
        indexes = @Index(name = "idx_history_issue", columnList = "coupon_issue_id"),
        check = @CheckConstraint(
                name = "chk_history_status",
                constraint = "prev_status in ('NONE','ISSUED','USED','EXPIRED') and new_status in ('ISSUED','USED','EXPIRED')"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "coupon_issue_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_history_issue")
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CouponIssue couponIssue;

    /**
     * 멱등성 키. Kafka Consumer의 CouponIssuedEvent.receiptId()와 동일한 값이 저장된다.
     * Producer가 발행한 receiptId → Consumer가 이 필드(request_id)에 매핑.
     */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /**
     * 전이 이전 상태. IssueStatus(coupon_issue 전용)와 구분하기 위해 HistoryPrevStatus를 사용.
     * 최초 발급은 NONE, 이후 상태 전이는 ISSUED/USED/EXPIRED 중 하나.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "prev_status", nullable = false, length = 20)
    private HistoryPrevStatus prevStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private IssueStatus newStatus;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 이 테이블은 Append-Only라 실제로 값이 바뀌는 일은 없지만, 컨벤션상 모든 테이블에 둔다. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CouponHistory(
            CouponIssue couponIssue,
            String requestId,
            HistoryPrevStatus prevStatus,
            IssueStatus newStatus,
            String cancelReason
    ) {
        this.couponIssue = couponIssue;
        this.requestId = requestId;
        this.prevStatus = prevStatus;
        this.newStatus = newStatus;
        this.cancelReason = cancelReason;
    }
}
