package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.service.ActiveTokenService.ConsumeResult;
import com.ureca.myureca.support.RedisKeys;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class ActiveTokenServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisScript<Long> consumeTokenScript;

    @InjectMocks
    private ActiveTokenService activeTokenService;

    private static final String TOKEN = "abc123token";
    private static final Long USER_ID = 42L;

    @Test
    void 유효한_토큰과_올바른_userId로_소비_시_OK를_반환한다() {
        when(redisTemplate.execute(eq(consumeTokenScript), anyList(), anyString()))
                .thenReturn(1L); // Lua: 성공

        ConsumeResult result = activeTokenService.consume(TOKEN, USER_ID);

        assertThat(result).isEqualTo(ConsumeResult.OK);
    }

    @Test
    void 토큰이_없거나_만료됐을_때_NOT_FOUND를_반환한다() {
        when(redisTemplate.execute(eq(consumeTokenScript), anyList(), anyString()))
                .thenReturn(0L); // Lua: 토큰 없음

        ConsumeResult result = activeTokenService.consume(TOKEN, USER_ID);

        assertThat(result).isEqualTo(ConsumeResult.NOT_FOUND);
    }

    @Test
    void 토큰_소유자_userId_불일치_시_USER_MISMATCH를_반환한다() {
        when(redisTemplate.execute(eq(consumeTokenScript), anyList(), anyString()))
                .thenReturn(-1L); // Lua: userId 불일치

        ConsumeResult result = activeTokenService.consume(TOKEN, USER_ID);

        assertThat(result).isEqualTo(ConsumeResult.USER_MISMATCH);
    }

    @Test
    void 토큰이_null이면_Redis를_호출하지_않고_NOT_FOUND를_반환한다() {
        ConsumeResult result = activeTokenService.consume(null, USER_ID);

        assertThat(result).isEqualTo(ConsumeResult.NOT_FOUND);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void 토큰이_빈_문자열이면_Redis를_호출하지_않고_NOT_FOUND를_반환한다() {
        ConsumeResult result = activeTokenService.consume("  ", USER_ID);

        assertThat(result).isEqualTo(ConsumeResult.NOT_FOUND);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void Lua_응답이_null이면_NOT_FOUND를_반환한다() {
        when(redisTemplate.execute(eq(consumeTokenScript), anyList(), anyString()))
                .thenReturn(null);

        ConsumeResult result = activeTokenService.consume(TOKEN, USER_ID);

        assertThat(result).isEqualTo(ConsumeResult.NOT_FOUND);
    }

    @Test
    void 올바른_Redis_키로_Lua_스크립트를_호출한다() {
        when(redisTemplate.execute(eq(consumeTokenScript), anyList(), anyString()))
                .thenReturn(1L);

        activeTokenService.consume(TOKEN, USER_ID);

        String expectedKey = RedisKeys.activeToken(TOKEN);
        verify(redisTemplate).execute(
                eq(consumeTokenScript),
                eq(List.of(expectedKey)),
                eq(String.valueOf(USER_ID))
        );
    }
}
