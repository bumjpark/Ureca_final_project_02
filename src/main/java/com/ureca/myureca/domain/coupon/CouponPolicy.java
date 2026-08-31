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

    /**
     * 지금 이 시점에 실제로 유효한 상태. <b>조회(표시)용으로는 저장된 {@link #status} 대신 이 값을
     * 써야 한다.</b>
     *
     * <p><b>왜 필요한가</b>: 저장된 {@code status}는 정책을 만들 때
     * {@link #determineInitialStatus}가 한 번 정하고 나면, {@code openAt}이 지나도 아무도 갱신하지
     * 않는다 — {@link #open()}을 호출하는 코드가 프로젝트 어디에도 없기 때문이다. 그래서 이미
     * 발급이 한창 진행 중인 정책이 관리자 화면에서 계속 "오픈전"으로 보였다(2026-08-31 실측:
     * 정책 9·10·14·19·20이 openAt이 한참 지났는데도 BEFORE_OPEN). 실제 발급 게이팅은 저장된
     * status가 아니라 {@code openAt} 시각 비교로 하기 때문에 동작에는 지장이 없었고, 화면 표시와
     * 상태 필터만 계속 틀렸다.
     *
     * <p><b>왜 스케줄러로 전이시키지 않는가</b>: BEFORE_OPEN ↔ OPEN은 순전히 시각으로 결정되는,
     * 저장할 필요가 없는 파생 값이다. 이걸 위해 배치를 하나 더 돌리면 쓰기와 스케줄러 부하만
     * 늘어난다(같은 날 스케줄러 스레드 고갈로 발급이 통째로 실패한 사례가 있다 —
     * {@code SchedulingConfig} 주석 참고). 반면 CLOSED/EXPIRED/DELETED는 시각만으로 되돌려
     * 계산할 수 없는 <b>진짜 상태 전이</b>라 저장된 값을 그대로 존중한다.
     */
    public CouponPolicyStatus effectiveStatusAt(LocalDateTime now) {
        if (this.status == CouponPolicyStatus.DELETED
                || this.status == CouponPolicyStatus.EXPIRED
                || this.status == CouponPolicyStatus.CLOSED) {
            return this.status;
        }
        if (this.openAt != null && now.isBefore(this.openAt)) {
            return CouponPolicyStatus.BEFORE_OPEN;
        }
        // 마감 기한이 지났으면 만료 배치가 아직 안 돌았더라도 "발급 중"은 아니다.
        // (CouponPolicyStatus.EXPIRED의 정의 자체가 "마감 기한 도달 또는 관리자 만료 처리"다.)
        if (this.closeAt != null && now.isAfter(this.closeAt)) {
            return CouponPolicyStatus.EXPIRED;
        }
        return CouponPolicyStatus.OPEN;
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
