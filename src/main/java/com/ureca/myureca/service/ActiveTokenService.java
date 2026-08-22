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
 *
 * <p>consume_token.lua 를 통해 GET+validate+DEL 을 원자적으로 실행한다.
 * 이를 통해 두 가지를 보장한다:
 * <ol>
 *   <li>TOCTOU 경쟁 없음: GET 후 DEL 사이의 틈새 중복 소비 원천 차단</li>
 *   <li>토큰 소유자 검증: 다른 userId 가 토큰을 도용해 발급 요청하는 것을 차단</li>
 * </ol>
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
     * @param token  대기열 진입 후 발급된 토큰
     * @param userId 발급 요청 유저 ID (토큰 소유자 검증에 사용)
     * @return 소비 결과 ({@link ConsumeResult})
     */
    public ConsumeResult consume(String token, Long userId) {
        if (token == null || token.isBlank()) {
            return ConsumeResult.NOT_FOUND;
        }

        String tokenKey = RedisKeys.activeToken(token);
        Long result = redisTemplate.execute(
                consumeTokenScript,
                List.of(tokenKey),
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
     *
     * <ul>
     *   <li>{@code OK}            – 정상 소비 완료</li>
     *   <li>{@code NOT_FOUND}     – 토큰 없음 (만료 또는 이미 소비됨)</li>
     *   <li>{@code USER_MISMATCH} – 토큰 소유자와 요청 userId 불일치 (도용 의심)</li>
     * </ul>
     */
    public enum ConsumeResult {
        OK, NOT_FOUND, USER_MISMATCH
    }
}
