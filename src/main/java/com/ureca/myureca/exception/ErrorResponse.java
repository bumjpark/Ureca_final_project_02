package com.ureca.myureca.exception;

/**
 * 에러 응답. message 에는 개인정보를 절대 담지 않는다 (FR-2 / NFR-5).
 * userId 같은 내부 식별자는 담아도 되지만 이름·이메일·연락처는 안 된다.
 */
public record ErrorResponse(String code, String message) {
}
