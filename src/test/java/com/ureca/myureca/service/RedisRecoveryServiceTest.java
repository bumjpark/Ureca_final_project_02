package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.dto.response.RedisRecoverResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.VerificationNotAllowedException;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.KafkaConsumerLagChecker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RedisRecoveryServiceTest {

    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponPolicyRepository couponPolicyRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private org.springframework.data.redis.core.ZSetOperations<String, String> zSetOperations;
    @Mock
    private KafkaConsumerLagChecker lagChecker;
    @Mock
    private RedisScript<Long> recoveryFinalizeScript;
    @Mock
    private RedisScript<Long> renewLockScript;
    @Mock
    private RedisScript<Long> releaseLockScript;
    @Mock
    private CouponPolicy policy;

    private RedisRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new RedisRecoveryService(couponIssueRepository, couponPolicyRepository, redisTemplate, lagChecker,
                recoveryFinalizeScript, renewLockScript, releaseLockScript);
        // @Value 필드는 스프링 컨테이너 없이 만든 유닛 테스트 인스턴스에는 주입되지 않으므로
        // 실제 컨슈머(KafkaCouponEventConsumer)와 동일한 기본값으로 직접 세팅한다.
        ReflectionTestUtils.setField(service, "consumerGroupId", "coupon-issue-consumer-group");
    }

    @Test
    void lag가_0이면_DB_기준으로_staging에_채운_뒤_finalize_스크립트로_원자적_교체한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(policy));
        when(policy.getId()).thenReturn(1L);
        when(policy.getTotalQuantity()).thenReturn(100);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("recover:lock:1"), anyString(), any(Duration.class))).thenReturn(true);
        when(lagChecker.getLag("coupon-issued-events", "coupon-issue-consumer-group")).thenReturn(0L);
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(10L, 20L, 30L));
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        RedisRecoverResponse response = service.recover(1L);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.issuedCount()).isEqualTo(3L);
        assertThat(response.remainingStock()).isEqualTo(97);
        assertThat(response.kafkaLag()).isZero();

        // staging 키에 먼저 채우고,
        verify(setOperations).add(eq("coupon:policy:1:issued:staging"), eq("10"), eq("20"), eq("30"));
        // 실제 stock/reserved/issued 키는 finalize 스크립트 한 번으로만 교체한다.
        verify(redisTemplate).execute(
                eq(recoveryFinalizeScript),
                eq(List.of("coupon:policy:1:stock", "coupon:policy:1:reserved",
                        "coupon:policy:1:issued", "coupon:policy:1:issued:staging")),
                eq("97"));
        // 락은 내가 잡은 토큰으로만 해제(compare-and-delete)한다.
        verify(redisTemplate).execute(eq(releaseLockScript), eq(List.of("recover:lock:1")), anyString());
    }

    @Test
    void lag가_0이_아니면_Redis를_건드리지_않고_즉시_실패한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("recover:lock:2"), anyString(), any(Duration.class))).thenReturn(true);
        when(lagChecker.getLag("coupon-issued-events", "coupon-issue-consumer-group")).thenReturn(42L);

        assertThatThrownBy(() -> service.recover(2L))
                .isInstanceOf(VerificationNotAllowedException.class)
                .hasMessageContaining("42");

        // lag 때문에 막힌 경우 staging/finalize 등 실제 데이터 작업을 절대 건드리면 안 된다.
        verify(redisTemplate, never()).opsForSet();
        verify(redisTemplate, never()).execute(eq(recoveryFinalizeScript), any(), any());
    }

    @Test
    void lag_조회가_실패하면_음수를_반환하고_안전하게_실패로_처리한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("recover:lock:3"), anyString(), any(Duration.class))).thenReturn(true);
        when(lagChecker.getLag("coupon-issued-events", "coupon-issue-consumer-group")).thenReturn(-1L);

        assertThatThrownBy(() -> service.recover(3L))
                .isInstanceOf(VerificationNotAllowedException.class);
    }

    @Test
    void 존재하지_않는_정책이면_CouponPolicyNotFoundException() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recover(999L))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }

    @Test
    void 이미_같은_정책_복구가_진행중이면_중복_실행을_막는다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("recover:lock:4"), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.recover(4L))
                .isInstanceOf(VerificationNotAllowedException.class)
                .hasMessageContaining("진행 중");
    }

    @Test
    void 발급된_유저가_없으면_staging_SET에_추가_호출을_하지_않고_실제_issued_키를_비운다() {
        when(policy.getId()).thenReturn(5L);
        when(policy.getTotalQuantity()).thenReturn(50);
        when(couponIssueRepository.findUserIdsByCouponPolicyId(5L)).thenReturn(List.of());

        RedisRecoverResponse response = service.syncPolicyRedisState(policy, 0L);

        assertThat(response.issuedCount()).isZero();
        assertThat(response.remainingStock()).isEqualTo(50);
        verify(redisTemplate, never()).opsForSet();
        verify(redisTemplate).execute(
                eq(recoveryFinalizeScript),
                eq(List.of("coupon:policy:5:stock", "coupon:policy:5:reserved",
                        "coupon:policy:5:issued", "coupon:policy:5:issued:staging")),
                eq("50"));
    }

    @Test
    void staging_구성중_새_발급이_반영되면_낡은_스냅샷으로_finalize하지_않고_중단한다() {
        when(policy.getId()).thenReturn(6L);
        when(policy.getTotalQuantity()).thenReturn(100);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        // 1번째 호출(초기 스냅샷) = 3명, 2번째 호출(finalize 직전 재확인) = 4명으로 바뀜
        when(couponIssueRepository.findUserIdsByCouponPolicyId(6L))
                .thenReturn(List.of(10L, 20L, 30L))
                .thenReturn(List.of(10L, 20L, 30L, 40L));

        assertThatThrownBy(() -> service.syncPolicyRedisState(policy, 0L))
                .isInstanceOf(VerificationNotAllowedException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("4");

        // 스냅샷이 낡은 걸 확인한 뒤이므로 실제 키 교체는 절대 실행하면 안 된다.
        verify(redisTemplate, never()).execute(eq(recoveryFinalizeScript), any(), any());
    }

    // ─── reconcileReservedDrift(부분 드리프트 정리) ───────────────────────────

    @Test
    void reserved가_비어있으면_아무것도_하지_않는다() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("coupon:policy:1:reserved", 0, -1)).thenReturn(java.util.Set.of());

        int fixed = service.reconcileReservedDrift(1L);

        assertThat(fixed).isZero();
        verify(couponIssueRepository, never())
                .findUserIdsByCouponPolicyIdAndUserIdIn(any(), any());
        verify(redisTemplate, never())
                .executePipelined(org.mockito.ArgumentMatchers
                        .<org.springframework.data.redis.core.RedisCallback<Object>>any());
    }

    @Test
    void reserved에_있지만_DB에_아직_없는_진행중인_발급은_건드리지_않는다() {
        // opsForZSet().range()는 Set<String>을 돌려주고 그 순회 순서는 보장되지 않으므로,
        // 넘어오는 후보 목록의 순서에 의존하지 않게 any()로 넓게 스텁한다.
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("coupon:policy:1:reserved", 0, -1))
                .thenReturn(java.util.Set.of("10", "20"));
        when(couponIssueRepository.findUserIdsByCouponPolicyIdAndUserIdIn(eq(1L), any()))
                .thenReturn(List.of());

        int fixed = service.reconcileReservedDrift(1L);

        assertThat(fixed).isZero();
        verify(redisTemplate, never())
                .executePipelined(org.mockito.ArgumentMatchers
                        .<org.springframework.data.redis.core.RedisCallback<Object>>any());
    }

    @Test
    void reserved에_있고_DB에도_이미_커밋된_건만_issued로_옮긴다() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("coupon:policy:1:reserved", 0, -1))
                .thenReturn(java.util.Set.of("10", "20", "30"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<Long>> candidateCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        // 10, 20은 DB에 이미 커밋됨(장애 중 confirmRedisState 콜백만 실패) — 30은 아직 진행 중이라 DB에 없음
        when(couponIssueRepository.findUserIdsByCouponPolicyIdAndUserIdIn(eq(1L), candidateCaptor.capture()))
                .thenReturn(List.of(10L, 20L));
        when(redisTemplate.executePipelined(org.mockito.ArgumentMatchers
                .<org.springframework.data.redis.core.RedisCallback<Object>>any()))
                .thenReturn(List.of());

        int fixed = service.reconcileReservedDrift(1L);

        assertThat(fixed).isEqualTo(2);
        assertThat(candidateCaptor.getValue()).containsExactlyInAnyOrder(10L, 20L, 30L);
        verify(redisTemplate).executePipelined(org.mockito.ArgumentMatchers
                .<org.springframework.data.redis.core.RedisCallback<Object>>any());
    }
}
