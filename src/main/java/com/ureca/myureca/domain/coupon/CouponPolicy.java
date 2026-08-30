package com.ureca.myureca.domain.coupon;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * coupon_policy 테이블 매핑 엔티티. 삼각 정합성(Redis / Kafka / DB)의 절대 기준값을 보관한다.
 */
@Entity
@Table(
        name = "coupon_policy",
        indexes = @Index(name = "idx_policy_open_at", columnList = "open_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_type", nullable = false, length = 20)
    private CouponType couponType;

    @Column(name = "discount_value", nullable = false)
    private Integer discountValue;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "open_at", nullable = false)
    private LocalDateTime openAt;

    @Column(name = "close_at")
    private LocalDateTime closeAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CouponPolicyStatus status;

    /** 소프트 삭제 일시. Redis 삭제가 선행된 후 기록된다. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CouponPolicy(
            String title,
            CouponType couponType,
            Integer discountValue,
            Integer totalQuantity,
            LocalDateTime openAt,
            LocalDateTime closeAt
    ) {
        this.title = title;
        this.couponType = couponType;
        this.discountValue = discountValue;
        this.totalQuantity = totalQuantity;
        this.openAt = openAt;
        this.closeAt = closeAt;
        this.status = determineInitialStatus(openAt);
    }

    public CouponPolicy(
            String title,
            CouponType couponType,
            Integer discountValue,
            Integer totalQuantity,
            LocalDateTime openAt,
            LocalDateTime closeAt,
            CouponPolicyStatus status
    ) {
        this.title = title;
        this.couponType = couponType;
        this.discountValue = discountValue;
        this.totalQuantity = totalQuantity;
        this.openAt = openAt;
        this.closeAt = closeAt;
        this.status = (status != null) ? status : determineInitialStatus(openAt);
    }

    private static CouponPolicyStatus determineInitialStatus(LocalDateTime openAt) {
        if (openAt != null && openAt.isAfter(LocalDateTime.now())) {
            return CouponPolicyStatus.BEFORE_OPEN;
        }
        return CouponPolicyStatus.OPEN;
    }

    /** 정책 오픈 전 수정에 사용한다. */
    public void update(
            String title,
            CouponType couponType,
            Integer discountValue,
            Integer totalQuantity,
            LocalDateTime openAt,
            LocalDateTime closeAt
    ) {
        this.title = title;
        this.couponType = couponType;
        this.discountValue = discountValue;
        this.totalQuantity = totalQuantity;
        this.openAt = openAt;
        this.closeAt = closeAt;
        if (this.status == CouponPolicyStatus.BEFORE_OPEN || this.status == CouponPolicyStatus.OPEN) {
            this.status = determineInitialStatus(openAt);
        }
    }

    /** 오픈 시각 도달 시 오픈 상태로 전환. */
    public void open() {
        this.status = CouponPolicyStatus.OPEN;
    }

    /** 재고 소진 등으로 인한 마감 처리. */
    public void close() {
        this.status = CouponPolicyStatus.CLOSED;
    }

    /** 마감 기한 도달 또는 관리자 만료 처리. */
    public void expire() {
        this.status = CouponPolicyStatus.EXPIRED;
    }

    /** Redis 삭제가 선행된 이후 호출되는 소프트 삭제. */
    public void softDelete() {
        this.status = CouponPolicyStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.status == CouponPolicyStatus.DELETED || this.deletedAt != null;
    }
}
