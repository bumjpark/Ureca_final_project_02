package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.response.CouponStatusResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CouponStatusServiceTest {

    private static final Long POLICY_ID = 1L;

    @Mock
    private CouponPolicyRepository couponPolicyRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CouponStatusService couponStatusService;

    @BeforeEach
    void setUp() {
        couponStatusService = new CouponStatusService(couponPolicyRepository, redisTemplate);
    }

    private CouponPolicy policyWithTotalQuantity(int totalQuantity) throws Exception {
        CouponPolicy policy = new CouponPolicy(
                "여름 시즌 정률 할인 쿠폰", CouponType.RATE, 10, totalQuantity,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        Field idField = CouponPolicy.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(policy, POLICY_ID);
        return policy;
    }

    @Test
    void 정상_상황에서는_발급완료_잔여수량_발급률이_올바르게_계산된다() throws Exception {
        CouponPolicy policy = policyWithTotalQuantity(10000);
        when(couponPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:" + POLICY_ID + ":stock")).thenReturn("3214");

        CouponStatusResponse response = couponStatusService.getCouponStatus(POLICY_ID);

        assertThat(response.totalQuantity()).isEqualTo(10000);
        assertThat(response.remainingQuantity()).isEqualTo(3214);
        assertThat(response.issuedQuantity()).isEqualTo(6786);
        assertThat(response.issueRate()).isEqualTo(67.86);
    }

    @Test
    void Redis에_재고_키가_없으면_전량_미발급으로_방어한다() throws Exception {
        CouponPolicy policy = policyWithTotalQuantity(10000);
        when(couponPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:" + POLICY_ID + ":stock")).thenReturn(null);

        CouponStatusResponse response = couponStatusService.getCouponStatus(POLICY_ID);

        assertThat(response.remainingQuantity()).isEqualTo(10000);
        assertThat(response.issuedQuantity()).isEqualTo(0);
        assertThat(response.issueRate()).isEqualTo(0.0);
    }

    @Test
    void 재고가_음수여도_화면에는_0으로_방어된다() throws Exception {
        CouponPolicy policy = policyWithTotalQuantity(10000);
        when(couponPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Lua의 Fast-Fail이 정상 동작한다면 나올 수 없는 값이지만, 방어로직 자체를 검증
        when(valueOperations.get("coupon:policy:" + POLICY_ID + ":stock")).thenReturn("-5");

        CouponStatusResponse response = couponStatusService.getCouponStatus(POLICY_ID);

        assertThat(response.remainingQuantity()).isEqualTo(0);
        assertThat(response.issuedQuantity()).isEqualTo(10000);
    }

    @Test
    void 존재하지_않는_정책이면_예외가_발생한다() {
        when(couponPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponStatusService.getCouponStatus(POLICY_ID))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }
}
