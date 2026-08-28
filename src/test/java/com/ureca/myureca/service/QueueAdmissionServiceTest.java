package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.support.RedisKeys;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QueueAdmissionServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ZSetOperations<String, String> zSetOperations;
    @Mock private QueueSseService queueSseService;

    @InjectMocks
    private QueueAdmissionService admissionService;

    private final Long POLICY_ID = 1L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(admissionService, "tokenTtlSeconds", 60L);
    }

    @Test
    void 대기열_유저_popMin_성공_시_Pipelined로_토큰을_일괄_발급한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("500");

        Set<TypedTuple<String>> popped = new HashSet<>();
        popped.add(new DefaultTypedTuple<>("42", 1.0));
        popped.add(new DefaultTypedTuple<>("43", 2.0));
        when(zSetOperations.popMin(RedisKeys.couponQueue(POLICY_ID), 300)).thenReturn(popped);

        int admitted = admissionService.admitUsers(POLICY_ID, 300);

        assertThat(admitted).isEqualTo(2);
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    void 잔여_재고가_배치_크기보다_작으면_잔여_재고만큼만_정밀_popMin_한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("10"); // 재고 10개

        Set<TypedTuple<String>> popped = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            popped.add(new DefaultTypedTuple<>(String.valueOf(i), (double) i));
        }
        // batchSize=300이지만 재고가 10개이므로 popMin에 10 전달
        when(zSetOperations.popMin(RedisKeys.couponQueue(POLICY_ID), 10)).thenReturn(popped);

        int admitted = admissionService.admitUsers(POLICY_ID, 300);

        assertThat(admitted).isEqualTo(10);
        verify(zSetOperations).popMin(RedisKeys.couponQueue(POLICY_ID), 10);
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    void 재고가_소진되었으면_대기열을_꺼내지_않고_0건_반환한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("0"); // 품절

        int admitted = admissionService.admitUsers(POLICY_ID, 300);

        assertThat(admitted).isEqualTo(0);
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void 재고_키가_존재하지_않으면_0건_반환한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn(null);

        int admitted = admissionService.admitUsers(POLICY_ID, 300);

        assertThat(admitted).isEqualTo(0);
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void 대기열이_비어있으면_0건_반환한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("500");
        when(zSetOperations.popMin(RedisKeys.couponQueue(POLICY_ID), 300)).thenReturn(Collections.emptySet());

        int admitted = admissionService.admitUsers(POLICY_ID, 300);

        assertThat(admitted).isEqualTo(0);
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    void 토큰_발급_파이프라인이_실패하면_popMin된_유저를_원래_순번으로_되돌리고_예외를_다시_던진다() {
        // 이슈 #22: popMin()은 이미 대기열에서 유저를 제거한 뒤라, 여기서 실패하면 보상(ZADD 원복)
        // 없이는 유저가 흔적 없이 대기열에서 사라진다.
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("500");

        Set<TypedTuple<String>> popped = new HashSet<>();
        popped.add(new DefaultTypedTuple<>("42", 10.0));
        popped.add(new DefaultTypedTuple<>("43", 11.0));
        when(zSetOperations.popMin(RedisKeys.couponQueue(POLICY_ID), 300)).thenReturn(popped);
        doThrow(new RuntimeException("Redis 파이프라인 실패"))
                .when(redisTemplate).executePipelined(any(SessionCallback.class));

        assertThatThrownBy(() -> admissionService.admitUsers(POLICY_ID, 300))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis 파이프라인 실패");

        // 원래 스코어(seq) 그대로 되돌렸는지 확인 — 순서가 바뀌면 안 되므로 정확한 스코어로 검증
        verify(zSetOperations).add(RedisKeys.couponQueue(POLICY_ID), "42", 10.0);
        verify(zSetOperations).add(RedisKeys.couponQueue(POLICY_ID), "43", 11.0);
    }

    @Test
    void 보상_ZADD_자체가_실패해도_원래_예외가_그대로_전파된다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("500");

        Set<TypedTuple<String>> popped = new HashSet<>();
        popped.add(new DefaultTypedTuple<>("42", 10.0));
        when(zSetOperations.popMin(RedisKeys.couponQueue(POLICY_ID), 300)).thenReturn(popped);
        doThrow(new RuntimeException("Redis 파이프라인 실패"))
                .when(redisTemplate).executePipelined(any(SessionCallback.class));
        doThrow(new RuntimeException("보상 ZADD도 실패"))
                .when(zSetOperations).add(eq(RedisKeys.couponQueue(POLICY_ID)), eq("42"), eq(10.0));

        assertThatThrownBy(() -> admissionService.admitUsers(POLICY_ID, 300))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis 파이프라인 실패");
    }
}
