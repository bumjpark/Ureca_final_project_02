package com.ureca.myureca.exception;

/**
 * 쿠폰 정책 생성/수정 시 단일 필드 검증(Bean Validation)으로는 표현할 수 없는
 * 교차 필드 비즈니스 규칙 위반 시 던지는 예외 (예: closeAt이 openAt보다 이전인 경우).
 */
public class InvalidCouponPolicyException extends RuntimeException {

    public InvalidCouponPolicyException(String message) {
        super(message);
    }
}
