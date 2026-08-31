package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.support.RedisKeys;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QueueAdmissionServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;
    @Mock private QueueSseService queueSseService;
    @Mock private RedisScript<List<String>> admitBatchScript;

    private QueueAdmissionService admissionService;

    private final Long POLICY_ID = 1L;

    @BeforeEach
    void setUp() {
        admissionService = new QueueAdmissionService(redisTemplate, queueSseService, admitBatchScript);
        ReflectionTestUtils.setField(admissionService, "tokenTtlSeconds", 60L);
        ReflectionTestUtils.setField(admissionService, "pendingReclaimGraceSeconds", 5L);
    }

    /**
     * admit_batch.lua 자체(재고 - pending개수만큼만 뽑는 계산)는 Lua 안에서 원자적으로
     * 일어나므로 이 유닛 테스트의 대상이 아니다 — 그 계산 로직 자체는 실제 Redis가 필요한
     * 통합 테스트(CouponIssueConcurrencyTest류)로 검증한다. 여기서는 admitUsers가 Lua 호출
     * 결과를 받아 토큰을 올바르게 일괄 발급/보상하는지만 본다.
     */
    @Test
    void Lua가_뽑은_유저_수만큼_Pipelined로_토큰을_일괄_발급한다() {
        when(redisTemplate.execute(eq(admitBatchScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of("42", "43"));

        int admitted = admissionService.admitUsers(POLICY_ID, 300);

        assertThat(admitted).isEqualTo(2);
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    void Lua가_아무도_안_뽑으면_파이프라인을_돌리지_않고_0건_반환한다() {
        when(redisTemplate.execute(eq(admitBatchScript), anyList(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        int admitted = admissionService.admitUsers(POLICY_ID, 300);

        assertThat(admitted).isEqualTo(0);
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    void Lua_응답이_null이어도_0건으로_처리한다() {
        when(redisTemplate.execute(eq(admitBatchScript), anyList(), anyString(), anyString()))
                .thenReturn(null);

        int admitted = admissionService.admitUsers(POLICY_ID, 300);

        assertThat(admitted).isEqualTo(0);
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    void 토큰_발급_파이프라인이_실패하면_pending에서_빼고_원래_순번으로_대기열에_되돌린다() {
        // 이슈 #22: admit_batch.lua가 이미 대기열에서 빼서 pending에 넣은 뒤다 — 여기서 실패하면
        // 보상(원복) 없이는 유저가 흔적 없이 대기열에서 사라진다.
        when(redisTemplate.execute(eq(admitBatchScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of("42", "43"));
        doThrow(new RuntimeException("Redis 파이프라인 실패"))
                .when(redisTemplate).executePipelined(any(SessionCallback.class));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score(RedisKeys.couponPending(POLICY_ID), "42")).thenReturn(10.0);
        when(zSetOperations.score(RedisKeys.couponPending(POLICY_ID), "43")).thenReturn(11.0);

        assertThatThrownBy(() -> admissionService.admitUsers(POLICY_ID, 300))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis 파이프라인 실패");

        // 원래 스코어(seq) 그대로 대기열에 되돌렸는지 확인
        verify(zSetOperations).add(RedisKeys.couponQueue(POLICY_ID), "42", 10.0);
        verify(zSetOperations).add(RedisKeys.couponQueue(POLICY_ID), "43", 11.0);
        // pending에서도 빠졌는지 확인 — 토큰을 못 받았으니 "발급 확정 대기 중"이 아니다.
        verify(zSetOperations).remove(RedisKeys.couponPending(POLICY_ID), "42");
        verify(zSetOperations).remove(RedisKeys.couponPending(POLICY_ID), "43");
    }

    @Test
    void 보상_ZADD_자체가_실패해도_원래_예외가_그대로_전파된다() {
        when(redisTemplate.execute(eq(admitBatchScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of("42"));
        doThrow(new RuntimeException("Redis 파이프라인 실패"))
                .when(redisTemplate).executePipelined(any(SessionCallback.class));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score(RedisKeys.couponPending(POLICY_ID), "42")).thenReturn(10.0);
        doThrow(new RuntimeException("보상 ZADD도 실패"))
                .when(zSetOperations).add(eq(RedisKeys.couponQueue(POLICY_ID)), eq("42"), eq(10.0));

        assertThatThrownBy(() -> admissionService.admitUsers(POLICY_ID, 300))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis 파이프라인 실패");
    }

    @Test
    void 임계_시간을_넘긴_pending_항목을_걷어내고_걷어낸_수를_반환한다() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.removeRangeByScore(eq(RedisKeys.couponPending(POLICY_ID)), anyDouble(), anyDouble()))
                .thenReturn(3L);

        long reclaimed = admissionService.reclaimStalePendingAdmissions(POLICY_ID);

        assertThat(reclaimed).isEqualTo(3L);
        verify(zSetOperations).removeRangeByScore(eq(RedisKeys.couponPending(POLICY_ID)), eq(Double.NEGATIVE_INFINITY), anyDouble());
    }

    @Test
    void 걷어낼_항목이_없으면_0을_반환한다() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.removeRangeByScore(any(), anyDouble(), anyDouble())).thenReturn(null);

        long reclaimed = admissionService.reclaimStalePendingAdmissions(POLICY_ID);

        assertThat(reclaimed).isEqualTo(0L);
    }
}
