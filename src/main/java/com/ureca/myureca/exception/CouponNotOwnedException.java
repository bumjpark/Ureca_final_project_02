package com.ureca.myureca.exception;

public class CouponNotOwnedException extends RuntimeException {

    public CouponNotOwnedException(Long couponIssueId) {
        super("본인의 쿠폰이 아닙니다. couponIssueId=" + couponIssueId);
    }
}
