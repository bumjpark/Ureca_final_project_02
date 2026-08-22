package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.request.QueueLimitUpdateRequest;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QueueLimitAdminServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private CouponPolicyRepository couponPolicyRepository;
    @Mock private ValueOperations<String, String> valueOperations;

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
}
