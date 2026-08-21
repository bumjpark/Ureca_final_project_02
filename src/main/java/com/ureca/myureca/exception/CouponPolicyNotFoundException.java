package com.ureca.myureca.exception;

public class CouponPolicyNotFoundException extends RuntimeException {

    public CouponPolicyNotFoundException(Long policyId) {
        super("쿠폰 정책을 찾을 수 없습니다. id=" + policyId);
    }
}
