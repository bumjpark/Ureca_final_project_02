package com.ureca.myureca.dto.coupon;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import java.time.LocalDateTime;

/**
 * POST /api/admin/coupon-policies 응답 바디. 별도 래퍼 없이 리소스를 그대로 반환한다.
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
