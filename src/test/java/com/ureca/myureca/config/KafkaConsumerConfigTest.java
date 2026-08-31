package com.ureca.myureca.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ureca.myureca.consumer.ConsumerFailureRecoverer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

/**
 * KafkaConsumerConfig 단위 테스트.
 *
 * <p>DefaultErrorHandler가 DataIntegrityViolationException을 non-retryable로 처리하는지 검증한다.
 *
 * <p>Spring Kafka 4.x의 ExceptionClassifier는 ExceptionMatcher 인터페이스를 통해 분류한다.
 * match(Throwable) 반환값: true = retryable, false = non-retryable (재시도 안 함)
 */
class KafkaConsumerConfigTest {

    private DefaultErrorHandler buildErrorHandler(ConsumerFailureRecoverer recoverer) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, ex) -> recoverer.recover(record, ex),
                new FixedBackOff(1_000L, 3L)
        );
        errorHandler.addNotRetryableExceptions(DataIntegrityViolationException.class);
        return errorHandler;
    }

    /**
     * 락 경합 분류({@code isTransientLockFailure})는 실제 config 인스턴스의 것을 그대로 호출한다 —
     * 위 {@link #buildErrorHandler}처럼 테스트에서 복제하면 정작 프로덕션 코드가 검증되지 않는다.
     */
    private KafkaConsumerConfig config() {
        return new KafkaConsumerConfig(mock(ConsumerFailureRecoverer.class), new ObjectMapper());
    }

    private boolean isTransientLockFailure(Exception ex) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(config(), "isTransientLockFailure", ex));
    }

    @Test
    void 데드락은_일시적_락_실패로_분류되어_전용_백오프를_탄다() {
        // MySQL 1213(Deadlock) → Spring 변환 시 CannotAcquireLockException(TransientDataAccessException)
        assertThat(isTransientLockFailure(
                new org.springframework.dao.CannotAcquireLockException("Deadlock found"))).isTrue();
    }

    @Test
    void 여러_겹_감싸인_락_예외도_원인_체인을_따라가_분류한다() {
        // 리스너에서 올라오는 예외는 BatchListenerFailedException 등으로 감싸여 있다.
        Exception wrapped = new org.springframework.kafka.listener.BatchListenerFailedException(
                "이벤트 단건 처리 실패",
                new IllegalStateException("중간 래핑",
                        new org.springframework.dao.CannotAcquireLockException("Lock wait timeout exceeded")),
                0);

        assertThat(isTransientLockFailure(wrapped)).isTrue();
    }

    @Test
    void UNIQUE_제약_위반은_락_경합이_아니므로_전용_백오프_대상이_아니다() {
        // 재시도해도 해소되지 않는 영구 실패 — 기본 백오프(짧게 소진 후 DLT)로 가야 한다.
        assertThat(isTransientLockFailure(
                new DataIntegrityViolationException("UNIQUE 제약 위반"))).isFalse();
        assertThat(isTransientLockFailure(new RuntimeException("일반 오류"))).isFalse();
    }

    @Test
    void DataIntegrityViolationException은_non_retryable로_등록되어_재시도하지_않는다() {
        // given
        DefaultErrorHandler errorHandler = buildErrorHandler(mock(ConsumerFailureRecoverer.class));

        // ExceptionMatcher.match(Throwable): true = retryable, false = non-retryable
        Object matcher = ReflectionTestUtils.invokeMethod(errorHandler, "getExceptionMatcher");

        // when
        Boolean isRetryable = ReflectionTestUtils.invokeMethod(matcher, "match",
                new DataIntegrityViolationException("UNIQUE 제약 위반"));

        // then: false = non-retryable → 재시도하지 않음
        assertThat(isRetryable).isFalse();
    }

    @Test
    void 일반_RuntimeException은_retryable로_처리되어_재시도한다() {
        // given
        DefaultErrorHandler errorHandler = buildErrorHandler(mock(ConsumerFailureRecoverer.class));
        Object matcher = ReflectionTestUtils.invokeMethod(errorHandler, "getExceptionMatcher");

        // when
        Boolean isRetryable = ReflectionTestUtils.invokeMethod(matcher, "match",
                new RuntimeException("DB 커넥션 오류"));

        // then: true = retryable → DefaultErrorHandler가 FixedBackOff로 재시도
        assertThat(isRetryable).isTrue();
    }
}
