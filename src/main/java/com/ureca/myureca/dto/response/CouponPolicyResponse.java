package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponPolicyStatus;
import com.ureca.myureca.domain.coupon.CouponType;
import java.time.LocalDateTime;

/**
 * 쿠폰 정책 목록/상세 조회 공통 응답 DTO.
 */
public record CouponPolicyResponse(
        Long id,
        String title,
        CouponType couponType,
        Integer discountValue,
        Integer totalQuantity,
        LocalDateTime openAt,
        LocalDateTime closeAt,
        CouponPolicyStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * 저장된 {@code status}가 아니라
     * {@link CouponPolicy#effectiveStatusAt(LocalDateTime)}(지금 시점 기준 실제 상태)을 담는다 —
     * 저장된 값은 오픈 시각이 지나도 갱신되지 않아 화면에 계속 "오픈전"으로 보인다. 자세한 배경은
     * 그 메서드 주석 참고.
     */
    public static CouponPolicyResponse from(CouponPolicy couponPolicy) {
        return new CouponPolicyResponse(
                couponPolicy.getId(),
                couponPolicy.getTitle(),
                couponPolicy.getCouponType(),
                couponPolicy.getDiscountValue(),
                couponPolicy.getTotalQuantity(),
                couponPolicy.getOpenAt(),
                couponPolicy.getCloseAt(),
                couponPolicy.effectiveStatusAt(LocalDateTime.now()),
                couponPolicy.getCreatedAt(),
                couponPolicy.getUpdatedAt());
    }
}
