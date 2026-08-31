package com.ureca.myureca.dto.request;

import com.ureca.myureca.domain.coupon.CouponType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * PATCH /api/admin/coupon-policies/{policyId} 요청 바디.
 */
public record CouponPolicyUpdateRequest(

        @NotBlank
        @Size(max = 100)
        String title,

        @NotNull
        CouponType couponType,

        @NotNull
        @Positive
        Integer discountValue,

        @NotNull
        @Positive
        Integer totalQuantity,

        @NotNull
        @Future
        LocalDateTime openAt,

        LocalDateTime closeAt
) {
}
