package com.ureca.myureca.exception;

import java.util.List;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String INVALID_REQUEST = "INVALID_REQUEST";

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        log.warn("user not found. userId={}", e.getUserId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage(), "USER_NOT_FOUND"));
    }

    /** status=FOO 처럼 enum 으로 변환 불가능한 쿼리 파라미터가 들어온 경우 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "요청 파라미터 '" + e.getName() + "' 의 값이 올바르지 않습니다.",
                        INVALID_REQUEST));
    }

    @ExceptionHandler(CouponSoldOutException.class)
    public ResponseEntity<ErrorResponse> handleSoldOut(CouponSoldOutException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage(), "OUT_OF_STOCK"));
    }

    @ExceptionHandler(CouponDuplicatedException.class)
    public ResponseEntity<ErrorResponse> handleDuplicated(CouponDuplicatedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage(), "ALREADY_ISSUED"));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), e.getMessage(), "INVALID_TOKEN"));
    }

    @ExceptionHandler(CouponPolicyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCouponPolicyNotFound(CouponPolicyNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage(), "COUPON_POLICY_NOT_FOUND"));
    }

    @ExceptionHandler(CouponIssueNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCouponIssueNotFound(CouponIssueNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage(), "COUPON_NOT_FOUND"));
    }

    @ExceptionHandler(QueueNotRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleQueueNotRegistered(QueueNotRegisteredException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage(), "QUEUE_NOT_REGISTERED"));
    }

    @ExceptionHandler(InvalidCouponPolicyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCouponPolicyException(InvalidCouponPolicyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage(), "INVALID_COUPON_POLICY"));
    }

    @ExceptionHandler(CouponNotOwnedException.class)
    public ResponseEntity<ErrorResponse> handleCouponNotOwned(CouponNotOwnedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), e.getMessage(), "COUPON_NOT_OWNED"));
    }

    @ExceptionHandler(CouponStatusConflictException.class)
    public ResponseEntity<ErrorResponse> handleCouponStatusConflict(CouponStatusConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage(), "INVALID_STATE_TRANSITION"));
    }

    @ExceptionHandler(ReconciliationLogNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationLogNotFound(ReconciliationLogNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage(), "RECONCILIATION_LOG_NOT_FOUND"));
    }

    @ExceptionHandler(ReconciliationTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationTypeNotSupported(
            ReconciliationTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage(), "RECONCILIATION_TYPE_NOT_SUPPORTED"));
    }

    @ExceptionHandler(ReconciliationAlreadySucceededException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationAlreadySucceeded(
            ReconciliationAlreadySucceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage(), "RECONCILIATION_ALREADY_SUCCEEDED"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "'" + e.getHeaderName() + "' 헤더는 필수입니다.",
                        INVALID_REQUEST));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage(), INVALID_REQUEST));
    }
    @ExceptionHandler(VerificationNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleVerificationNotAllowed(VerificationNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage(), "VERIFICATION_NOT_ALLOWED"));
    }

    @ExceptionHandler(ReconciliationBulkDispatchException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationBulkDispatch(ReconciliationBulkDispatchException e) {
        log.error("재처리 접수 중 일부 실패. 이미 접수된 로그={}", e.getDispatchedLogIds(), e);
        List<String> errors = e.getDispatchedLogIds().stream()
                .map(id -> "이미 접수됨(재실행 불필요): logId=" + id)
                .toList();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage(), errors,
                        "RECONCILIATION_BULK_DISPATCH_FAILED"));
    }

    @ExceptionHandler(VerificationDispatchException.class)
    public ResponseEntity<ErrorResponse> handleVerificationDispatch(VerificationDispatchException e) {
        log.error("검증 배치 접수 중 일부 실패. 이미 접수된 정책={}", e.getDispatchedPolicyIds(), e);
        List<String> errors = e.getDispatchedPolicyIds().stream()
                .map(id -> "이미 접수됨(재실행 불필요): policyId=" + id)
                .toList();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage(), errors,
                        "VERIFICATION_DISPATCH_FAILED"));
    }

    @ExceptionHandler(VerificationReportNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleVerificationReportNotFound(VerificationReportNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage(), "VERIFICATION_REPORT_NOT_FOUND"));
    }

    @ExceptionHandler(VerificationReportCsvNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleVerificationReportCsvNotAvailable(
            VerificationReportCsvNotAvailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage(), "VERIFICATION_REPORT_CSV_NOT_AVAILABLE"));
    }

    @ExceptionHandler(VerificationReportFileMissingException.class)
    public ResponseEntity<ErrorResponse> handleVerificationReportFileMissing(
            VerificationReportFileMissingException e) {
        log.error("검증 리포트 CSV 파일이 디스크에 없음(DB row는 존재). {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ErrorResponse.of(HttpStatus.GONE.value(), e.getMessage(), "VERIFICATION_REPORT_FILE_MISSING"));
    }

    @ExceptionHandler(QueueFullException.class)
    public ResponseEntity<ErrorResponse> handleQueueFull(QueueFullException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(), e.getMessage(), "QUEUE_FULL"));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), e.getMessage(), "TOO_MANY_REQUESTS"));
    }

    /**
     * 쿠폰 오픈 전/종료 후 접근.
     * 오픈 전인 경우 openAt(오픈 예정 시각)을 errors 필드에 포함 — FR-10 요구사항.
     */
    @ExceptionHandler(CouponNotOpenedException.class)
    public ResponseEntity<ErrorResponse> handleCouponNotOpened(CouponNotOpenedException e) {
        List<String> details = e.getOpenAt() != null
                ? List.of("openAt: " + e.getOpenAt())
                : null;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage(), details, "COUPON_NOT_OPENED"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();

        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "입력값이 올바르지 않습니다",
                errors,
                INVALID_REQUEST);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    private String formatFieldError(FieldError fieldError) {
        return "%s: %s".formatted(fieldError.getField(), fieldError.getDefaultMessage());
    }

    /**
     * 필수 요청 파라미터(@RequestParam) 누락
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
                        "필수 파라미터가 누락되었습니다: " + e.getParameterName(),
                        INVALID_REQUEST));
    }

    /**
     * 요청 본문을 못 읽는 경우(깨진 JSON, 인코딩 문제 등)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "요청 본문을 읽을 수 없습니다.", INVALID_REQUEST));
    }

    /** 지원하지 않는 HTTP 메서드로 호출한 경우 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED.value(), e.getMessage(), "METHOD_NOT_ALLOWED"));
    }

    /** 정적 리소스(favicon 등) 또는 미존재 엔드포인트 요청 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage(), "NO_SUCH_ENDPOINT"));
    }

    /**
     * Redis 연결 실패/명령 타임아웃(Lua 실행 도중 Redis가 죽었거나 응답이 없는 경우).
     *
     * <p>이 셋을 하나로 묶는 이유: {@code QueueService}/{@code RedisCouponIssueService}의
     * {@code redisTemplate.execute(...)}(Lua 실행)가 Redis 장애 시 던지는 예외가 Lettuce
     * 드라이버 버전과 장애 형태(연결 자체 실패 vs 응답 타임아웃 vs 프로토콜 오류)에 따라
     * 갈리기 때문이다. 이 핸들러가 추가되기 전에는 셋 다 {@link #handleUnexpected}의 500
     * "일시적인 오류가 발생했습니다"로 뭉개져, 부하테스트 스크립트가 원인을 알 수 없는 실패로
     * 세거나 응답 메시지 문자열을 사후에 매칭해야 했다(k6 {@code joinOtherError}류 버킷).
     *
     * <p>503인 이유: 요청 자체는 유효하고 Redis가 복구되면 같은 요청이 성공할 수 있는
     * 일시적 장애다.
     */
    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class, QueryTimeoutException.class})
    public ResponseEntity<ErrorResponse> handleRedisUnavailable(Exception e) {
        log.error("Redis 연결 실패 또는 명령 타임아웃", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.",
                        "REDIS_UNAVAILABLE"));
    }

    /**
     * HikariCP 커넥션 획득 실패({@code connection-timeout} 초과) — {@code @Transactional} 프록시가
     * 트랜잭션을 열려는 시점에 던져지므로 서비스 메서드 내부의 어떤 {@code catch}로도 못 잡는다
     * (`backend-feat-147` 비교 조사에서 확인한 것과 동일한 지점, `lock-and-transaction-settings.md`
     * §6.1 참고). 이 핸들러가 없으면 500으로 뭉개져 "풀 고갈"과 "진짜 미확인 결함"을 구분할 수 없다.
     *
     * <p>503인 이유: 요청 자체는 유효하고 풀에 여유가 생기면 같은 요청이 성공할 수 있는 일시적
     * 자원 고갈이다.
     */
    @ExceptionHandler(CannotCreateTransactionException.class)
    public ResponseEntity<ErrorResponse> handleConnectionUnavailable(CannotCreateTransactionException e) {
        log.error("DB 커넥션 획득 실패(풀 고갈 의심)", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.",
                        "DB_CONNECTION_UNAVAILABLE"));
    }

    /**
     * 위에서 못 잡은 예상 못한 예외(NPE 등 — 위 두 핸들러가 알려진 인프라 장애는 이미 분리했으므로,
     * 여기 도달하는 건 재현 가능한 버그일 가능성이 위 두 경우보다 높다).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
                        "INTERNAL_ERROR"));
    }
}

