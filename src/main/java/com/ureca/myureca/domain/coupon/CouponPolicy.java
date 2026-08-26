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
 *
 * <p>coupon_type 컬럼은 DDL에 DB CHECK 제약(chk_*)이 없으나 값 도메인이 고정되어 있어
 * 코드 안전성을 위해 enum으로 매핑했다. ({@code @Column(check = ...)}는 달지 않음 - DDL 근거 없음)
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

    /** 실시간 카운터가 아닌, 검증 배치가 사후에 채워넣는 조회/스냅샷 전용 값. */
    @Column(name = "issued_quantity", nullable = false)
    private Integer issuedQuantity = 0;

    @Column(name = "open_at", nullable = false)
    private LocalDateTime openAt;

    @Column(name = "close_at")
    private LocalDateTime closeAt;

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
        this.issuedQuantity = 0;
        this.openAt = openAt;
        this.closeAt = closeAt;
    }

    /** 검증 배치가 DB 발급 집계 수량으로 스냅샷을 동기화할 때 사용한다. */
    public void syncIssuedQuantity(int issuedQuantity) {
        this.issuedQuantity = issuedQuantity;
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
    }

    /** Redis 삭제가 선행된 이후 호출되는 소프트 삭제. */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
