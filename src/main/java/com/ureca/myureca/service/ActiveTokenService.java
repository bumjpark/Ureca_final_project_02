package com.ureca.myureca.service;

import com.ureca.myureca.support.RedisKeys;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 대기열 통과 토큰(activeToken) 소비 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActiveTokenService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> consumeTokenScript;

    /** Lua 스크립트 반환값 */
    private static final long RESULT_OK = 1L;
    private static final long RESULT_NOT_FOUND = 0L;
    private static final long RESULT_USER_MISMATCH = -1L;

    /**
     * activeToken 을 소비하고 결과를 반환한다.
     *
     * @param token    대기열 진입 후 발급된 토큰
     * @param policyId 대상 쿠폰 정책 ID
     * @param userId   발급 요청 유저 ID (토큰 소유자 검증에 사용)
     * @return 소비 결과 ({@link ConsumeResult})
     */
    public ConsumeResult consume(String token, Long policyId, Long userId) {
        if (token == null || token.isBlank()) {
            return ConsumeResult.NOT_FOUND;
        }

        String tokenKey = RedisKeys.activeToken(token);
        String userKey = (policyId != null && userId != null) ? RedisKeys.activeUser(policyId, userId) : tokenKey;

        Long result = redisTemplate.execute(
                consumeTokenScript,
                List.of(tokenKey, userKey),
                String.valueOf(userId)
        );

        if (result == null) {
            log.warn("consume_token.lua 응답이 null. token={}", token);
            return ConsumeResult.NOT_FOUND;
        }

        if (RESULT_OK == result) {
            return ConsumeResult.OK;
        }
        if (RESULT_USER_MISMATCH == result) {
            log.warn("토큰 소유자 불일치. token={}, requestedUserId={}", token, userId);
            return ConsumeResult.USER_MISMATCH;
        }
        return ConsumeResult.NOT_FOUND;
    }

    /**
     * activeToken 소비 결과.
     */
    public enum ConsumeResult {
        OK, NOT_FOUND, USER_MISMATCH
    }
}
