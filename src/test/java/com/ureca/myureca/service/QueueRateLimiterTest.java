package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ureca.myureca.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueRateLimiterTest {

    private QueueRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new QueueRateLimiter();
    }

    @Test
    void 첫_번째_요청은_정상_통과한다() {
        assertThatCode(() -> rateLimiter.checkRateLimit(1L, 42L))
                .doesNotThrowAnyException();
    }

    @Test
    void 동일_유저가_1초_이내_연타_호출_시_TooManyRequestsException이_발생한다() {
        rateLimiter.checkRateLimit(1L, 42L);

        assertThatThrownBy(() -> rateLimiter.checkRateLimit(1L, 42L))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void 다른_유저는_각각_독립적으로_제한을_받는다() {
        rateLimiter.checkRateLimit(1L, 42L);

        // 다른 유저(43L)는 1초 이내라도 통과
        assertThatCode(() -> rateLimiter.checkRateLimit(1L, 43L))
                .doesNotThrowAnyException();
    }
}
