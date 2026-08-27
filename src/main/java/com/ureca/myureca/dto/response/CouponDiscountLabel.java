package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.coupon.CouponPolicy;

final class CouponDiscountLabel {

    private CouponDiscountLabel() {
    }

    static String of(CouponPolicy policy) {
        return switch (policy.getCouponType()) {
            case FIXED -> String.format("%,d원 할인", policy.getDiscountValue());
            case RATE -> policy.getDiscountValue() + "% 할인";
        };
    }
}
