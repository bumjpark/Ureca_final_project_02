package com.ureca.myureca.domain.verification;

import com.ureca.myureca.domain.coupon.CouponPolicy;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * verification_report 테이블 매핑 엔티티.
 * 300만 건 규모 삼각 정합성(DB coupon_issue / Redis RESERVED) 검증 배치의 실행 리포트.
 */
@Entity
@Table(
        name = "verification_report",
        indexes = @Index(name = "idx_verification_policy", columnList = "coupon_policy_id, run_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "coupon_policy_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_verification_policy")
    )
    private CouponPolicy couponPolicy;

    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;

    @Column(name = "total_issued", nullable = false)
    private Integer totalIssued;

    @Column(name = "total_reserved", nullable = false)
    private Integer totalReserved;

    @Column(name = "mismatch_count", nullable = false)
    private Integer mismatchCount;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            check = @CheckConstraint(name = "chk_verification_status", constraint = "status in ('PENDING','SUCCESS','MISMATCH_FOUND')")
    )
    private VerificationStatus status;

    @Column(name = "report_url", length = 255)
    private String reportUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public VerificationReport(
            CouponPolicy couponPolicy,
            LocalDateTime runAt,
            Integer totalIssued,
            Integer totalReserved,
            Integer mismatchCount,
            VerificationStatus status
    ) {
        this.couponPolicy = couponPolicy;
        this.runAt = runAt;
        this.totalIssued = totalIssued;
        this.totalReserved = totalReserved;
        this.mismatchCount = mismatchCount;
        this.status = status;
    }

    /** 비동기 실행 접수 직후, 백그라운드 처리 전에 즉시 저장할 임시 리포트. */
    public static VerificationReport pending(CouponPolicy couponPolicy, LocalDateTime runAt) {
        return new VerificationReport(couponPolicy, runAt, 0, 0, 0, VerificationStatus.PENDING);
    }

    /** 백그라운드 처리가 끝나면 PENDING 리포트를 최종 결과로 확정한다. */
    public void complete(int totalIssued, int totalReserved, int mismatchCount, VerificationStatus finalStatus) {
        if (this.status != VerificationStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 완료 처리할 수 있습니다. 현재 상태: " + this.status);
        }
        if (finalStatus == VerificationStatus.PENDING) {
            throw new IllegalArgumentException("완료 상태는 PENDING일 수 없습니다.");
        }
        this.totalIssued = totalIssued;
        this.totalReserved = totalReserved;
        this.mismatchCount = mismatchCount;
        this.status = finalStatus;
    }

    /** 불일치 상세 내역 CSV가 생성된 이후 파일 경로를 리포트에 연결한다. */
    public void attachReportUrl(String reportUrl) {
        this.reportUrl = reportUrl;
    }
}
