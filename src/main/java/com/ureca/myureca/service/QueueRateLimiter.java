package com.ureca.myureca.service;

import com.ureca.myureca.exception.TooManyRequestsException;
import com.ureca.myureca.support.RedisKeys;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 대기열 진입 시 동일 유저의 매크로/광클(연타) 공격을 차단하는 분산 RateLimiter.
 * Redis 분산 키(rate_limit:{policyId}:{userId}) 기반으로 다중 서버 인스턴스 환경에서도 일관되게 1초당 1회 요청을 제한한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueRateLimiter {

    private static final long RATE_LIMIT_TTL_MS = 1000L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 유저의 대기열 진입 요청 주기를 검증한다.
     * 1초 이내 재호출 시 TooManyRequestsException(429) 발생.
     */
    public void checkRateLimit(Long policyId, Long userId) {
        String key = RedisKeys.rateLimit(policyId, userId);
        try {
            Boolean isFirstRequest = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", RATE_LIMIT_TTL_MS, TimeUnit.MILLISECONDS);

            if (!Boolean.TRUE.equals(isFirstRequest)) {
                throw new TooManyRequestsException();
            }
        } catch (TooManyRequestsException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis Rate Limiter 일시 장애 발생 (요청 통과 fallback). policyId={}, userId={}", policyId, userId, e);
        }
    }
}

