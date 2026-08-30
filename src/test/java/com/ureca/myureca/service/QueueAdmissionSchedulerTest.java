package com.ureca.myureca.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class QueueAdmissionSchedulerTest {

    @Mock private CouponPolicyCacheService couponPolicyCacheService;
    @Mock private QueueAdmissionService queueAdmissionService;
    @Mock private QueueLimitAdminService queueLimitAdminService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private org.springframework.data.redis.core.ZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private QueueAdmissionScheduler scheduler;

    private final Long POLICY_ID = 1L;
    private CouponPolicyCacheService.CachedPolicy activePolicy;

    @BeforeEach
    void setUp() {
        activePolicy = new CouponPolicyCacheService.CachedPolicy(
                POLICY_ID,
                LocalDateTime.now().minusHours(1), // 이미 오픈됨
                LocalDateTime.now().plusDays(1),
                System.currentTimeMillis()
        );
    }

    @Test
    void 오픈_중인_정책에_대해_분산_락_획득_성공_시_동적_Limit으로_admitUsers를_호출한다() {
        when(couponPolicyCacheService.getActivePolicies())
                .thenReturn(List.of(activePolicy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.zCard(RedisKeys.couponQueue(POLICY_ID))).thenReturn(100L);
        when(valueOperations.setIfAbsent(eq(RedisKeys.lockAdmission(POLICY_ID)), eq("locked"), eq(1L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("100");
        // 잔여 재고(100)를 같은 틱에서 읽은 값 그대로 스케일링 계산에 넘겨야 한다 — 두 번 읽으면
        // 서로 다른 시점의 값이 섞이고, 재고 대비 안전 상한(이슈 #8)이 엉뚱한 기준으로 계산된다.
        when(queueLimitAdminService.calculateAutoScaledLimit(POLICY_ID, 100L, 100L))
                .thenReturn(500); // 동적 Limit 500

        scheduler.processQueueAdmission();

        verify(queueAdmissionService).admitUsers(POLICY_ID, 500);
        verify(queueLimitAdminService).calculateAutoScaledLimit(POLICY_ID, 100L, 100L);
        // 재고를 읽기 전에 이탈자부터 걷어내야, 그 몫만큼 가용량 계산이 과소평가되지 않는다.
        verify(queueAdmissionService).reclaimStalePendingAdmissions(POLICY_ID);
    }

    @Test
    void 다른_서버가_이미_락을_선점했으면_admitUsers도_reclaim도_호출하지_않는다() {
        when(couponPolicyCacheService.getActivePolicies())
                .thenReturn(List.of(activePolicy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(RedisKeys.lockAdmission(POLICY_ID)), eq("locked"), eq(1L), eq(TimeUnit.SECONDS)))
                .thenReturn(false); // 락 획득 실패

        scheduler.processQueueAdmission();

        verify(queueAdmissionService, never()).admitUsers(anyLong(), anyInt());
        verify(queueAdmissionService, never()).reclaimStalePendingAdmissions(anyLong());
    }

    @Test
    void 오픈_전인_정책은_스케줄러_대상에서_제외된다() {
        CouponPolicyCacheService.CachedPolicy futurePolicy = new CouponPolicyCacheService.CachedPolicy(
                2L,
                LocalDateTime.now().plusHours(2), null,
                System.currentTimeMillis()
        );

        when(couponPolicyCacheService.getActivePolicies())
                .thenReturn(List.of(futurePolicy));

        scheduler.processQueueAdmission();

        verify(queueAdmissionService, never()).admitUsers(anyLong(), anyInt());
    }

    @Test
    void 재고가_0인_정책은_admitUsers를_호출하지_않고_스킵한다() {
        when(couponPolicyCacheService.getActivePolicies())
                .thenReturn(List.of(activePolicy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(RedisKeys.lockAdmission(POLICY_ID)), eq("locked"), eq(1L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("0");

        scheduler.processQueueAdmission();

        verify(queueAdmissionService, never()).admitUsers(anyLong(), anyInt());
        // 재고가 0이어도 이탈자 회수는 계속 돈다 — pending을 비워두지 않으면 재입고/보정 시
        // 다음 admitUsers 계산이 여전히 과소평가된 채로 시작한다.
        verify(queueAdmissionService).reclaimStalePendingAdmissions(POLICY_ID);
    }
}
