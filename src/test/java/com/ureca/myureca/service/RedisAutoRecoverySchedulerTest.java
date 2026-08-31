package com.ureca.myureca.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.response.ComponentHealthResponse;
import com.ureca.myureca.dto.response.HealthResponse;
import com.ureca.myureca.dto.response.RedisRecoverResponse;
import com.ureca.myureca.exception.VerificationNotAllowedException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisAutoRecoverySchedulerTest {

    @Mock
    private CouponPolicyRepository couponPolicyRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisRecoveryService redisRecoveryService;
    @Mock
    private VerificationAsyncTrigger verificationAsyncTrigger;
    @Mock
    private HealthCheckService healthCheckService;

    @InjectMocks
    private RedisAutoRecoveryScheduler scheduler;

    private static final Map<String, ComponentHealthResponse> REDIS_UP = Map.of(
            "mysql", ComponentHealthResponse.up(1),
            "redis", ComponentHealthResponse.up(1),
            "kafka", ComponentHealthResponse.up(1));

    private static final Map<String, ComponentHealthResponse> REDIS_DOWN = Map.of(
            "mysql", ComponentHealthResponse.up(1),
            "redis", ComponentHealthResponse.down(1500, "Currently not connected. Commands are rejected."),
            "kafka", ComponentHealthResponse.up(1));

    private CouponPolicy activePolicy(Long id) {
        CouponPolicy policy = new CouponPolicy(
                "테스트 쿠폰", CouponType.FIXED, 1000, 10000,
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1));
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "id", id);
        return policy;
    }

    @Test
    void Redis가_DOWN이면_정책_조회조차_하지_않고_건너뛴다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_DOWN));

        scheduler.recoverMissingStock();

        verify(couponPolicyRepository, never()).findByDeletedAtIsNull(any(Pageable.class));
        verify(redisRecoveryService, never()).recover(anyLong());
    }

    @Test
    void stock_키가_이미_있으면_recover를_호출하지_않는다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L))));
        when(redisTemplate.hasKey(RedisKeys.couponStock(1L))).thenReturn(true);

        scheduler.recoverMissingStock();

        verify(redisRecoveryService, never()).recover(anyLong());
    }

    @Test
    void stock_키가_없으면_자동으로_recover를_호출한다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L))));
        when(redisTemplate.hasKey(RedisKeys.couponStock(1L))).thenReturn(false);
        when(redisRecoveryService.recover(1L)).thenReturn(
                RedisRecoverResponse.success(1L, 10000, 0L, 10000, 0L));

        scheduler.recoverMissingStock();

        verify(redisRecoveryService, times(1)).recover(1L);
    }

    @Test
    void lag로_인해_recover가_실패해도_예외가_전파되지_않는다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L))));
        when(redisTemplate.hasKey(RedisKeys.couponStock(1L))).thenReturn(false);
        when(redisRecoveryService.recover(1L))
                .thenThrow(new VerificationNotAllowedException("lag가 아직 0이 아닙니다"));

        scheduler.recoverMissingStock(); // 예외 없이 조용히 넘어가야 한다(다음 틱에 재시도)

        verify(redisRecoveryService, times(1)).recover(1L);
    }

    @Test
    void 예상치_못한_예외도_전파되지_않고_다음_정책_처리를_막지_않는다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L), activePolicy(2L))));
        when(redisTemplate.hasKey(RedisKeys.couponStock(1L))).thenReturn(false);
        when(redisTemplate.hasKey(RedisKeys.couponStock(2L))).thenReturn(false);
        when(redisRecoveryService.recover(1L)).thenThrow(new IllegalStateException("예상 못한 오류"));
        when(redisRecoveryService.recover(2L)).thenReturn(
                RedisRecoverResponse.success(2L, 5000, 0L, 5000, 0L));

        scheduler.recoverMissingStock();

        verify(redisRecoveryService, times(1)).recover(1L);
        verify(redisRecoveryService, times(1)).recover(2L); // 1L 실패가 2L 처리를 막지 않음
    }

    @Test
    void 여러_활성_정책_중_stock_키_없는_것만_recover한다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L), activePolicy(2L))));
        when(redisTemplate.hasKey(RedisKeys.couponStock(1L))).thenReturn(true);
        when(redisTemplate.hasKey(RedisKeys.couponStock(2L))).thenReturn(false);
        when(redisRecoveryService.recover(2L)).thenReturn(
                RedisRecoverResponse.success(2L, 5000, 0L, 5000, 0L));

        scheduler.recoverMissingStock();

        verify(redisRecoveryService, never()).recover(1L);
        verify(redisRecoveryService, times(1)).recover(2L);
    }

    // ─── reconcileReservedDrift(부분 드리프트 정리) ───────────────────────────

    @Test
    void Redis가_DOWN이면_드리프트_정리도_건너뛴다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_DOWN));

        scheduler.reconcileReservedDrift();

        verify(couponPolicyRepository, never()).findByDeletedAtIsNull(any(Pageable.class));
        verify(redisRecoveryService, never()).reconcileReservedDrift(anyLong());
    }

    @Test
    void 활성_정책마다_reconcileReservedDrift를_호출한다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L), activePolicy(2L))));
        when(redisRecoveryService.reconcileReservedDrift(1L)).thenReturn(0);
        when(redisRecoveryService.reconcileReservedDrift(2L)).thenReturn(3);

        scheduler.reconcileReservedDrift();

        verify(redisRecoveryService, times(1)).reconcileReservedDrift(1L);
        verify(redisRecoveryService, times(1)).reconcileReservedDrift(2L);
    }

    @Test
    void 드리프트_정리_중_예상못한_예외가_나도_다른_정책_처리를_막지_않는다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L), activePolicy(2L))));
        when(redisRecoveryService.reconcileReservedDrift(1L))
                .thenThrow(new IllegalStateException("예상 못한 오류"));
        when(redisRecoveryService.reconcileReservedDrift(2L)).thenReturn(1);

        scheduler.reconcileReservedDrift(); // 예외가 전파되지 않아야 한다

        verify(redisRecoveryService, times(1)).reconcileReservedDrift(1L);
        verify(redisRecoveryService, times(1)).reconcileReservedDrift(2L);
    }

    @Test
    void 만료된_정책은_stock_복구와_마찬가지로_드리프트_정리_대상에서도_제외된다() {
        CouponPolicy closedPolicy = activePolicy(3L);
        org.springframework.test.util.ReflectionTestUtils.setField(
                closedPolicy, "closeAt", LocalDateTime.now().minusHours(1));
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(closedPolicy)));

        scheduler.reconcileReservedDrift();

        verify(redisRecoveryService, never()).reconcileReservedDrift(anyLong());
    }

    // ─── detectStaleReservedDrift(Check D 자동 탐지·등록) ───────────────────────

    @Test
    void Redis가_DOWN이면_미아_예약_탐지도_건너뛴다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_DOWN));

        scheduler.detectStaleReservedDrift();

        verify(couponPolicyRepository, never()).findByDeletedAtIsNull(any(Pageable.class));
        verify(verificationAsyncTrigger, never()).detectAndRegisterStaleReserved(anyLong());
    }

    @Test
    void 활성_정책마다_detectAndRegisterStaleReserved를_호출한다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L), activePolicy(2L))));
        when(verificationAsyncTrigger.detectAndRegisterStaleReserved(1L)).thenReturn(0);
        when(verificationAsyncTrigger.detectAndRegisterStaleReserved(2L)).thenReturn(2);

        scheduler.detectStaleReservedDrift();

        verify(verificationAsyncTrigger, times(1)).detectAndRegisterStaleReserved(1L);
        verify(verificationAsyncTrigger, times(1)).detectAndRegisterStaleReserved(2L);
    }

    @Test
    void 미아_예약_탐지_중_예상못한_예외가_나도_다른_정책_처리를_막지_않는다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy(1L), activePolicy(2L))));
        when(verificationAsyncTrigger.detectAndRegisterStaleReserved(1L))
                .thenThrow(new IllegalStateException("예상 못한 오류"));
        when(verificationAsyncTrigger.detectAndRegisterStaleReserved(2L)).thenReturn(1);

        scheduler.detectStaleReservedDrift(); // 예외가 전파되지 않아야 한다

        verify(verificationAsyncTrigger, times(1)).detectAndRegisterStaleReserved(1L);
        verify(verificationAsyncTrigger, times(1)).detectAndRegisterStaleReserved(2L);
    }

    @Test
    void 만료된_정책은_미아_예약_탐지_대상에서도_제외된다() {
        CouponPolicy closedPolicy = activePolicy(3L);
        org.springframework.test.util.ReflectionTestUtils.setField(
                closedPolicy, "closeAt", LocalDateTime.now().minusHours(1));
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_UP));
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(closedPolicy)));

        scheduler.detectStaleReservedDrift();

        verify(verificationAsyncTrigger, never()).detectAndRegisterStaleReserved(anyLong());
    }
}
