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
    private KafkaConsumerLagChecker lagChecker;
    @Mock
    private CouponPolicy policy;

    private RedisRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new RedisRecoveryService(couponIssueRepository, couponPolicyRepository, redisTemplate, lagChecker);
    }

    @Test
    void lag가_0이면_DB_기준으로_Redis를_재구성한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(policy));
        when(policy.getId()).thenReturn(1L);
        when(policy.getTotalQuantity()).thenReturn(100);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("recover:lock:1"), anyString(), any(Duration.class))).thenReturn(true);
        when(lagChecker.getLag("coupon-issued-events", "coupon-service")).thenReturn(0L);
        when(couponIssueRepository.countByCouponPolicyId(1L)).thenReturn(30L);
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(10L, 20L, 30L));
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        RedisRecoverResponse response = service.recover(1L);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.issuedCount()).isEqualTo(30L);
        assertThat(response.remainingStock()).isEqualTo(70);
        assertThat(response.kafkaLag()).isZero();
    }

    @Test
    void lag가_0이_아니면_Redis를_건드리지_않고_즉시_실패한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("recover:lock:2"), anyString(), any(Duration.class))).thenReturn(true);
        when(lagChecker.getLag("coupon-issued-events", "coupon-service")).thenReturn(42L);

        assertThatThrownBy(() -> service.recover(2L))
                .isInstanceOf(VerificationNotAllowedException.class)
                .hasMessageContaining("42");

        // lag 때문에 막힌 경우 Redis SET/DEL을 절대 건드리면 안 된다 (락 관련 호출은 예외)
        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    void lag_조회가_실패하면_음수를_반환하고_안전하게_실패로_처리한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("recover:lock:3"), anyString(), any(Duration.class))).thenReturn(true);
        when(lagChecker.getLag("coupon-issued-events", "coupon-service")).thenReturn(-1L);

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
    void 발급된_유저가_없으면_issued_SET에_추가_호출을_하지_않는다() {
        when(policy.getId()).thenReturn(5L);
        when(policy.getTotalQuantity()).thenReturn(50);
        when(couponIssueRepository.countByCouponPolicyId(5L)).thenReturn(0L);
        when(couponIssueRepository.findUserIdsByCouponPolicyId(5L)).thenReturn(List.of());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.syncPolicyRedisState(policy, 0L);

        verify(redisTemplate, never()).opsForSet();
    }
}