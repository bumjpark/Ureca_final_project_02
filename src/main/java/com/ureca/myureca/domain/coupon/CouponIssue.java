package com.ureca.myureca.domain.coupon;

import com.ureca.myureca.domain.user.User;
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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * coupon_issue 테이블 매핑 엔티티. 300만 건 규모의 대용량 핵심 테이블.
 *
 * <p>상태는 ISSUED(발급) / USED(사용) / EXPIRED(만료) 3종만 존재하며,
 * 사용 취소는 CANCELED로 가지 않고 USED → ISSUED로 복귀한다.
 */
@Entity
@Table(
        name = "coupon_issue",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_policy_user",
                columnNames = {"coupon_policy_id", "user_id"}
        ),
        indexes = {
                @Index(name = "idx_issue_user", columnList = "user_id"),
                @Index(name = "idx_issue_status", columnList = "coupon_policy_id, status"),
                @Index(name = "idx_policy_issued", columnList = "coupon_policy_id, issued_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

    @Id
    // IDENTITY는 INSERT 직후 생성 키를 즉시 조회해야 해서 매 save()마다 flush를 강제한다.
    // SEQUENCE는 ID를 allocationSize(50)개씩 미리 캐싱해 쓰므로 즉시 flush가 불필요해지고,
    // JDBC batch insert(hibernate.jdbc.batch_size)도 가능해진다. (V7 마이그레이션 참고)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "coupon_issue_seq")
    @SequenceGenerator(name = "coupon_issue_seq", sequenceName = "coupon_issue_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "coupon_policy_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_issue_policy")
    )
    private CouponPolicy couponPolicy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_issue_user")
    )
    private User user;

    @Column(name = "receipt_id", nullable = false, length = 64)
    private String receiptId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            check = @CheckConstraint(name = "chk_issue_status", constraint = "status in ('ISSUED','USED','EXPIRED')")
    )
    private IssueStatus status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CouponIssue(CouponPolicy couponPolicy, User user, String receiptId, LocalDateTime issuedAt) {
        this.couponPolicy = couponPolicy;
        this.user = user;
        this.receiptId = receiptId;
        this.issuedAt = issuedAt;
        this.status = IssueStatus.ISSUED;
    }

    /** 쿠폰 사용 처리: ISSUED → USED. */
    public void markUsed(LocalDateTime usedAt) {
        if (this.status != IssueStatus.ISSUED) {
            throw new IllegalStateException("ISSUED 상태에서만 사용 처리할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = IssueStatus.USED;
        this.usedAt = usedAt;
    }

    /** 사용 취소 처리: USED → ISSUED (CANCELED 상태는 존재하지 않음). */
    public void cancelUse() {
        if (this.status != IssueStatus.USED) {
            throw new IllegalStateException("USED 상태에서만 사용 취소할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = IssueStatus.ISSUED;
        this.usedAt = null;
    }

    /** 만료 처리: ISSUED → EXPIRED. */
    public void expire() {
        if (this.status != IssueStatus.ISSUED) {
            throw new IllegalStateException("ISSUED 상태에서만 만료 처리할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = IssueStatus.EXPIRED;
    }

    public boolean isExpiredAt(LocalDateTime now) {
        if (this.status != IssueStatus.ISSUED) {
            return false;
        }
        LocalDateTime closeAt = this.couponPolicy.getCloseAt();
        return closeAt != null && closeAt.isBefore(now);
    }

    public boolean isUsableAt(LocalDateTime now) {
        return this.status == IssueStatus.ISSUED && !isExpiredAt(now);
    }
}
