package com.ureca.myureca.dto;

import com.ureca.myureca.domain.coupon.CouponPolicy;
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
        Integer issuedQuantity,
        LocalDateTime openAt,
        LocalDateTime closeAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static CouponPolicyResponse from(CouponPolicy couponPolicy) {
        return new CouponPolicyResponse(
                couponPolicy.getId(),
                couponPolicy.getTitle(),
                couponPolicy.getCouponType(),
                couponPolicy.getDiscountValue(),
                couponPolicy.getTotalQuantity(),
                couponPolicy.getIssuedQuantity(),
                couponPolicy.getOpenAt(),
                couponPolicy.getCloseAt(),
                couponPolicy.getCreatedAt(),
                couponPolicy.getUpdatedAt()
        );
    }
}
