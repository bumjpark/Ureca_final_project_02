package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ureca.myureca.exception.TooManyRequestsException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class QueueRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private QueueRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new QueueRateLimiter(redisTemplate);
    }

    @Test
    void 첫_번째_요청은_Redis_SETNX_성공으로_정상_통과한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        assertThatCode(() -> rateLimiter.checkRateLimit(1L, 42L))
                .doesNotThrowAnyException();
    }

    @Test
    void 동일_유저가_1초_이내_연타_호출_시_SETNX_실패로_TooManyRequestsException이_발생한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> rateLimiter.checkRateLimit(1L, 42L))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void Redis_일시_장애_발생_시_Graceful_통과_fallback한다() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis Connection Refused"));

        assertThatCode(() -> rateLimiter.checkRateLimit(1L, 42L))
                .doesNotThrowAnyException();
    }
}

