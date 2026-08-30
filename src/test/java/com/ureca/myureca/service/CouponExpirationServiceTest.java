package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.repository.CouponIssueRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponExpirationServiceTest {

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @InjectMocks
    private CouponExpirationService couponExpirationService;

    @Test
    @DisplayName("expireAllCoupons: 만료된 정책들의 ISSUED 쿠폰을 벌크 EXPIRED 처리한다")
    void expireAllCoupons_success() {
        // given
        when(couponIssueRepository.bulkExpireAllExpired(any(LocalDateTime.class))).thenReturn(15);

        // when
        int result = couponExpirationService.expireAllCoupons();

        // then
        assertThat(result).isEqualTo(15);
        verify(couponIssueRepository).bulkExpireAllExpired(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("expireCouponsByPolicyId: 특정 정책의 ISSUED 쿠폰을 벌크 EXPIRED 처리한다")
    void expireCouponsByPolicyId_success() {
        // given
        Long policyId = 10L;
        when(couponIssueRepository.bulkExpireByPolicyId(eq(policyId), any(LocalDateTime.class))).thenReturn(5);

        // when
        int result = couponExpirationService.expireCouponsByPolicyId(policyId);

        // then
        assertThat(result).isEqualTo(5);
        verify(couponIssueRepository).bulkExpireByPolicyId(eq(policyId), any(LocalDateTime.class));
    }
}
