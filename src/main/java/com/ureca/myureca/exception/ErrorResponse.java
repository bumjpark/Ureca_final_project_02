package com.ureca.myureca.exception;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공통 에러 응답 바디. 성공 응답은 래퍼 없이 리소스를 그대로 반환하지만,
 * 에러 응답은 클라이언트가 파싱할 수 있도록 일관된 형태를 유지한다.
 */
public record ErrorResponse(
        int status,
        String message,
        List<String> errors,
        LocalDateTime timestamp
) {

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, message, null, LocalDateTime.now());
    }

    public static ErrorResponse of(int status, String message, List<String> errors) {
        return new ErrorResponse(status, message, errors, LocalDateTime.now());
    }
}
