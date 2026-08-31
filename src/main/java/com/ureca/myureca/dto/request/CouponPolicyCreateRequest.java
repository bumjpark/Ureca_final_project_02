package com.ureca.myureca.dto.request;

import com.ureca.myureca.domain.coupon.CouponType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * POST /api/admin/coupon-policies 요청 바디.
 *
 * <p>{@code closeAt}이 {@code openAt}보다 이전인지, {@code couponType == RATE}일 때
 * {@code discountValue}가 할인율 범위(1~100)를 넘는지 같은 교차 필드 검증은
 * 어노테이션으로 표현할 수 없어 서비스 레이어({@code CouponPolicyService})에서 처리한다.
 */
public record CouponPolicyCreateRequest(

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
