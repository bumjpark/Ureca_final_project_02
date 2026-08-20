package com.ureca.myureca.dto.response;

public record CouponStatusResponse(
        Long policyId,
        int totalQuantity,
        int issuedQuantity,
        int remainingQuantity,
        double issueRate
) {
}