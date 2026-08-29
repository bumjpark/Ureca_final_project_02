package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.request.QueueLimitUpdateRequest;
import com.ureca.myureca.dto.response.QueueAdminStatusResponse;
import com.ureca.myureca.dto.response.QueueLimitResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QueueLimitAdminServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private CouponPolicyRepository couponPolicyRepository;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private QueueLimitAdminService limitAdminService;

    private final Long POLICY_ID = 1L;
    private CouponPolicy policy;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(limitAdminService, "defaultAdmissionRate", 300);

        policy = new CouponPolicy(
                "테스트 쿠폰", CouponType.FIXED, 1000, 10000,
                LocalDateTime.now().minusHours(1), null
        );
        ReflectionTestUtils.setField(policy, "id", POLICY_ID);
    }

    @Test
    void 특정_정책의_Limit을_수정하면_Redis에_정책별_키로_저장된다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        QueueLimitResponse response = limitAdminService.updateLimit(new QueueLimitUpdateRequest(POLICY_ID, 500));

        assertThat(response.policyId()).isEqualTo(POLICY_ID);
        assertThat(response.limit()).isEqualTo(500);
        verify(valueOperations).set(RedisKeys.queueLimit(POLICY_ID), "500");
    }

    @Test
    void policyId가_null이면_글로벌_기본_Limit으로_Redis에_저장된다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        QueueLimitResponse response = limitAdminService.updateLimit(new QueueLimitUpdateRequest(null, 600));

        assertThat(response.policyId()).isNull();
        assertThat(response.limit()).isEqualTo(600);
        verify(valueOperations).set(RedisKeys.queueDefaultLimit(), "600");
    }

    @Test
    void 존재하지_않는_정책_ID로_Limit_수정_시_CouponPolicyNotFoundException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> limitAdminService.updateLimit(new QueueLimitUpdateRequest(999L, 500)))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }

    @Test
    void 정책별_Limit이_설정되어_있으면_해당_값을_최우선으로_반환한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.queueLimit(POLICY_ID))).thenReturn("800");

        int effectiveLimit = limitAdminService.getEffectiveLimit(POLICY_ID);

        assertThat(effectiveLimit).isEqualTo(800);
    }

    @Test
    void 정책별_Limit이_없으면_글로벌_Limit을_2순위로_반환한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.queueLimit(POLICY_ID))).thenReturn(null);
        when(valueOperations.get(RedisKeys.queueDefaultLimit())).thenReturn("450");

        int effectiveLimit = limitAdminService.getEffectiveLimit(POLICY_ID);

        assertThat(effectiveLimit).isEqualTo(450);
    }

    @Test
    void Redis에_아무_설정도_없으면_기본_설정값_300을_반환한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.queueLimit(POLICY_ID))).thenReturn(null);
        when(valueOperations.get(RedisKeys.queueDefaultLimit())).thenReturn(null);

        int effectiveLimit = limitAdminService.getEffectiveLimit(POLICY_ID);

        assertThat(effectiveLimit).isEqualTo(300);
    }

    /** 자동 스케일링 테스트용 — 정책별/글로벌 Limit 없이 기본값(300)으로 흐르게 한다. */
    private void stubNoConfiguredLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.queueLimit(POLICY_ID))).thenReturn(null);
        when(valueOperations.get(RedisKeys.queueDefaultLimit())).thenReturn(null);
    }

    @Test
    void 대기열_인원이_5000명_이상_급증하면_Limit이_2배로_자동_스케일링된다() {
        stubNoConfiguredLimit();

        // 기본 300 -> 5000명 대기 시 600. 재고 100,000이라 5% 상한(5,000)에 걸리지 않는다.
        int autoLimit = limitAdminService.calculateAutoScaledLimit(POLICY_ID, 6000L, 100_000L);

        assertThat(autoLimit).isEqualTo(600);
    }

    @Test
    void 대기열_인원이_10000명_이상_폭증하면_Limit이_3배로_자동_스케일링된다() {
        stubNoConfiguredLimit();

        // 기본 300 -> 12000명 대기 시 900. 재고 100,000이라 5% 상한(5,000)에 걸리지 않는다.
        int autoLimit = limitAdminService.calculateAutoScaledLimit(POLICY_ID, 12000L, 100_000L);

        assertThat(autoLimit).isEqualTo(900);
    }

    /**
     * 이슈 #8: 한 틱의 배치가 잔여 재고에 비해 크면 과다 입장 → 선착순 역전이 발생한다
     * (실측: 재고 10,000에 2,000 = 20% → FCFS 역전 356쌍). 자동 스케일링이 그 구간까지
     * 스스로 올라가지 못하도록 잔여 재고의 5%로 조인다.
     */
    @Test
    void 자동_스케일링은_잔여_재고의_5퍼센트를_넘지_못한다() {
        stubNoConfiguredLimit();

        // 대기열 12,000명이면 원래 900이지만, 재고 10,000의 5% = 500으로 조여진다.
        int autoLimit = limitAdminService.calculateAutoScaledLimit(POLICY_ID, 12000L, 10_000L);

        assertThat(autoLimit).isEqualTo(500);
    }

    @Test
    void 재고_대비_상한이_운영자가_설정한_기본_Limit보다_작아도_기본값_아래로는_깎지_않는다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.queueLimit(POLICY_ID))).thenReturn("300");

        // 잔여 재고 1,000의 5% = 50이지만, 운영자가 명시한 300은 자동 가드가 건드리지 않는다.
        int autoLimit = limitAdminService.calculateAutoScaledLimit(POLICY_ID, 12000L, 1_000L);

        assertThat(autoLimit).isEqualTo(300);
    }

    @Test
    void 잔여_재고를_알_수_없으면_자동_스케일링하지_않고_기본_Limit을_쓴다() {
        stubNoConfiguredLimit();

        // -1 = stock 키 미초기화/파싱 실패. 근거 없이 확장하지 않는다.
        int autoLimit = limitAdminService.calculateAutoScaledLimit(POLICY_ID, 12000L, -1L);

        assertThat(autoLimit).isEqualTo(300);
    }

    @Test
    void limit이_1미만이면_IllegalArgumentException이_발생한다() {
        assertThatThrownBy(() -> limitAdminService.updateLimit(new QueueLimitUpdateRequest(POLICY_ID, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이상");
    }

    @Test
    void limit이_50000초과이면_IllegalArgumentException이_발생한다() {
        assertThatThrownBy(() -> limitAdminService.updateLimit(new QueueLimitUpdateRequest(POLICY_ID, 50001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이하");
    }

    @Test
    void 대기열_현황_조회는_ZSET_크기와_현재_적용_Limit을_함께_반환한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.size(RedisKeys.couponQueue(POLICY_ID))).thenReturn(1234L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.queueLimit(POLICY_ID))).thenReturn("700");

        QueueAdminStatusResponse response = limitAdminService.getStatus(POLICY_ID);

        assertThat(response.policyId()).isEqualTo(POLICY_ID);
        assertThat(response.waitingCount()).isEqualTo(1234L);
        assertThat(response.currentLimit()).isEqualTo(700);
        assertThat(response.usingDefaultLimit()).isFalse();
    }

    @Test
    void 정책별_Limit이_없으면_대기열_현황_조회에서_글로벌_기본값을_쓰고_있음을_표시한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.size(RedisKeys.couponQueue(POLICY_ID))).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.queueLimit(POLICY_ID))).thenReturn(null);
        when(valueOperations.get(RedisKeys.queueDefaultLimit())).thenReturn(null);

        QueueAdminStatusResponse response = limitAdminService.getStatus(POLICY_ID);

        assertThat(response.waitingCount()).isEqualTo(0L);
        assertThat(response.currentLimit()).isEqualTo(300);
        assertThat(response.usingDefaultLimit()).isTrue();
    }

    @Test
    void 존재하지_않는_정책의_대기열_현황을_조회하면_CouponPolicyNotFoundException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> limitAdminService.getStatus(999L))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }
}
