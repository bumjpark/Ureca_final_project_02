package com.ureca.myureca.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CouponIssueNotFoundException extends RuntimeException {

    public CouponIssueNotFoundException(Long couponIssueId) {
        super("존재하지 않는 쿠폰 발급 건입니다. couponIssueId=" + couponIssueId);
    }
}
