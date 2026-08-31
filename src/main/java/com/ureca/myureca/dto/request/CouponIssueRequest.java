package com.ureca.myureca.dto.request;

import jakarta.validation.constraints.NotNull;

public record CouponIssueRequest(
                @NotNull(message = "userId는 필수입니다.") Long userId) {
}
