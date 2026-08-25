package com.ureca.myureca.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
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

    @ExceptionHandler(ReconciliationLogNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationLogNotFound(ReconciliationLogNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage()));
    }

    @ExceptionHandler(ReconciliationTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationTypeNotSupported(
            ReconciliationTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
    }

    @ExceptionHandler(ReconciliationAlreadySucceededException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationAlreadySucceeded(
            ReconciliationAlreadySucceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage()));
    }

    @ExceptionHandler(ReconciliationBulkDispatchException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationBulkDispatch(ReconciliationBulkDispatchException e) {
        log.error("재처리 접수 중 일부 실패. 이미 접수된 로그={}", e.getDispatchedLogIds(), e);
        List<String> errors = e.getDispatchedLogIds().stream()
                .map(id -> "이미 접수됨(재실행 불필요): logId=" + id)
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
}