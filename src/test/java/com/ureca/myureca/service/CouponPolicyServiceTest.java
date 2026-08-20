package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.CouponPolicyCreateRequest;
import com.ureca.myureca.dto.CouponPolicyResponse;
import com.ureca.myureca.exception.InvalidCouponPolicyException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponPolicyServiceTest {

    @Mock
    private CouponPolicyRepository couponPolicyRepository;

    private CouponPolicyService couponPolicyService;

    @BeforeEach
    void setUp() {
        couponPolicyService = new CouponPolicyService(couponPolicyRepository);
    }

    @Test
    void 정책_생성에_성공하면_저장된_값을_응답으로_반환한다() {
        LocalDateTime openAt = LocalDateTime.now().plusDays(1);
        CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                "여름 휴가 쿠폰", CouponType.FIXED, 5000, 10000, openAt, null
        );
        CouponPolicy saved = new CouponPolicy(
                request.title(), request.couponType(), request.discountValue(),
                request.totalQuantity(), request.openAt(), request.closeAt()
        );
        when(couponPolicyRepository.save(any(CouponPolicy.class))).thenReturn(saved);

        CouponPolicyResponse response = couponPolicyService.createCouponPolicy(request);

        assertThat(response.title()).isEqualTo("여름 휴가 쿠폰");
        assertThat(response.couponType()).isEqualTo(CouponType.FIXED);
        assertThat(response.discountValue()).isEqualTo(5000);
        assertThat(response.totalQuantity()).isEqualTo(10000);
        assertThat(response.issuedQuantity()).isEqualTo(0);
        verify(couponPolicyRepository).save(any(CouponPolicy.class));
    }

    @Test
    void closeAt이_openAt보다_이전이면_예외가_발생한다() {
        LocalDateTime openAt = LocalDateTime.now().plusDays(2);
        LocalDateTime closeAt = LocalDateTime.now().plusDays(1);
        CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                "잘못된 기간 쿠폰", CouponType.FIXED, 1000, 100, openAt, closeAt
        );

        assertThatThrownBy(() -> couponPolicyService.createCouponPolicy(request))
                .isInstanceOf(InvalidCouponPolicyException.class)
                .hasMessageContaining("closeAt");
    }

    @Test
    void RATE_타입에서_할인율이_100을_초과하면_예외가_발생한다() {
        LocalDateTime openAt = LocalDateTime.now().plusDays(1);
        CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                "잘못된 할인율 쿠폰", CouponType.RATE, 150, 100, openAt, null
        );

        assertThatThrownBy(() -> couponPolicyService.createCouponPolicy(request))
                .isInstanceOf(InvalidCouponPolicyException.class)
                .hasMessageContaining("RATE");
    }
}
