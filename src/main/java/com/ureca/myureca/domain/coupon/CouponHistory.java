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

/**
 * coupon_history 테이블 매핑 엔티티. 불변(Append-Only) 감사 로그.
 *
 * <p>request_id는 클라이언트 멱등성 키로, UNIQUE 제약(uk_history_request)을 통해
 * 동일 요청의 중복 반영을 DB 레벨에서 차단한다.
 */
@Entity
@Table(
        name = "coupon_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_history_request", columnNames = "request_id"),
        indexes = @Index(name = "idx_history_issue", columnList = "coupon_issue_id"),
        check = @CheckConstraint(
                name = "chk_history_status",
                constraint = "prev_status in ('ISSUED','USED','EXPIRED') and new_status in ('ISSUED','USED','EXPIRED')"
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

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "prev_status", nullable = false, length = 20)
    private IssueStatus prevStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private IssueStatus newStatus;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public CouponHistory(
            CouponIssue couponIssue,
            String requestId,
            IssueStatus prevStatus,
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
