package com.ureca.myureca.dto.response;

/**
 * 만료 처리 API 응답 DTO.
 */
public record CouponPolicyExpirationResponse(
        Long policyId,
        int affectedPolicies,
        int affectedCoupons,
        String message) {
}
