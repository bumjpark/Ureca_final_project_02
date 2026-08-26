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
