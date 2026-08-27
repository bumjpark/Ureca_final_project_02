package com.ureca.myureca.dto.request;

import com.ureca.myureca.domain.coupon.IssueStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CouponUseRequest(

        @NotNull(message = "userId는 필수입니다.")
        Long userId,

        @NotNull(message = "status는 필수입니다. (ISSUED / USED / EXPIRED)")
        IssueStatus status,

        @Size(max = 255, message = "reason은 255자를 넘을 수 없습니다.")
        String reason
) {
}
