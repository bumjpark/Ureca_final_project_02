package com.ureca.myureca.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        log.warn("user not found. userId={}", e.getUserId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage()));
    }

    /** status=FOO 처럼 enum 으로 변환 불가능한 쿼리 파라미터가 들어온 경우 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "요청 파라미터 '" + e.getName() + "' 의 값이 올바르지 않습니다."));
    }

    @ExceptionHandler(CouponSoldOutException.class)
    public ResponseEntity<ErrorResponse> handleSoldOut(CouponSoldOutException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
    }

    @ExceptionHandler(CouponDuplicatedException.class)
    public ResponseEntity<ErrorResponse> handleDuplicated(CouponDuplicatedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage()));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), e.getMessage()));
    }

    @ExceptionHandler(CouponPolicyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCouponPolicyNotFound(CouponPolicyNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage()));
    }

    @ExceptionHandler(InvalidCouponPolicyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCouponPolicyException(InvalidCouponPolicyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
    }

    @ExceptionHandler(VerificationNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleVerificationNotAllowed(VerificationNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage()));
    }

    @ExceptionHandler(VerificationDispatchException.class)
    public ResponseEntity<ErrorResponse> handleVerificationDispatch(VerificationDispatchException e) {
        log.error("검증 배치 접수 중 일부 실패. 이미 접수된 정책={}", e.getDispatchedPolicyIds(), e);
        List<String> errors = e.getDispatchedPolicyIds().stream()
                .map(id -> "이미 접수됨(재실행 불필요): policyId=" + id)
                .toList();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage(), errors));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();

        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "입력값이 올바르지 않습니다",
                errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    private String formatFieldError(FieldError fieldError) {
        return "%s: %s".formatted(fieldError.getField(), fieldError.getDefaultMessage());
    }

    /**
     * 요청 본문을 못 읽는 경우(깨진 JSON, 인코딩 문제 등)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "요청 본문을 읽을 수 없습니다."));
    }

    /** 지원하지 않는 HTTP 메서드로 호출한 경우 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED.value(), e.getMessage()));
    }

    /**
     * 위에서 못 잡은 예상 못한 예외(Redis/DB 장애, NPE 등)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."));
    }
}