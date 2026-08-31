package com.ureca.myureca.exception;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공통 에러 응답 바디. 성공 응답은 래퍼 없이 리소스를 그대로 반환하지만,
 * 에러 응답은 클라이언트가 파싱할 수 있도록 일관된 형태를 유지한다.
 *
 * <p>{@code errorCode}는 클라이언트가 {@code message}(사람이 읽는 문장, 자유롭게 바뀔 수 있음)를
 * 문자열 매칭하지 않고도 실패 원인을 기계적으로 분류할 수 있게 하는 안정적인 식별자다.
 * {@link GlobalExceptionHandler}의 모든 핸들러가 코드를 채우므로, 이 API가 내보내는 오류 응답은
 * 전부 {@code errorCode}를 갖는다. <b>코드 문자열은 클라이언트·부하테스트 스크립트가 분기하는
 * 계약이므로 한 번 정한 값을 바꾸지 않는다</b>(메시지 문구는 자유롭게 바꿔도 된다).
 *
 * <p>{@code null}을 허용하는 팩토리({@link #of(int, String)} 등)를 남겨둔 것은 과거 호출부
 * 호환용이다 — 새 핸들러를 추가할 때는 반드시 코드를 받는 오버로드를 쓴다.
 */
public record ErrorResponse(
        int status,
        String message,
        List<String> errors,
        LocalDateTime timestamp,
        String errorCode
) {

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, message, null, LocalDateTime.now(), null);
    }

    public static ErrorResponse of(int status, String message, List<String> errors) {
        return new ErrorResponse(status, message, errors, LocalDateTime.now(), null);
    }

    public static ErrorResponse of(int status, String message, String errorCode) {
        return new ErrorResponse(status, message, null, LocalDateTime.now(), errorCode);
    }

    public static ErrorResponse of(int status, String message, List<String> errors, String errorCode) {
        return new ErrorResponse(status, message, errors, LocalDateTime.now(), errorCode);
    }
}
