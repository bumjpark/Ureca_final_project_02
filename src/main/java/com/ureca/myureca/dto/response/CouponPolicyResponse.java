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

    public static CouponPolicyResponse from(CouponPolicy couponPolicy) {
        return new CouponPolicyResponse(
                couponPolicy.getId(),
                couponPolicy.getTitle(),
                couponPolicy.getCouponType(),
                couponPolicy.getDiscountValue(),
                couponPolicy.getTotalQuantity(),
                couponPolicy.getOpenAt(),
                couponPolicy.getCloseAt(),
                couponPolicy.getStatus(),
                couponPolicy.getCreatedAt(),
                couponPolicy.getUpdatedAt());
    }
}
