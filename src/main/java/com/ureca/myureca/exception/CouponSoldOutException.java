package com.ureca.myureca.exception;

public class CouponSoldOutException extends RuntimeException {
    public CouponSoldOutException(String message) {
        super(message);
    }
}
