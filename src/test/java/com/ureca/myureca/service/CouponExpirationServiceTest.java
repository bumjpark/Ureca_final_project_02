package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponPolicyStatus;
import com.ureca.myureca.dto.response.CouponPolicyExpirationResponse;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponExpirationServiceTest {

    @Mock
    private CouponPolicyRepository couponPolicyRepository;

    @Mock
    private CouponPolicyCacheService couponPolicyCacheService;

    @Mock
    private CouponExpirationChunkExecutor chunkExecutor;

    @InjectMocks
    private CouponExpirationService couponExpirationService;

    private CouponPolicy createPolicy(Long id) {
        CouponPolicy policy = org.mockito.Mockito.mock(CouponPolicy.class);
        when(policy.getId()).thenReturn(id);
        return policy;
    }

    @Test
    @DisplayName("expireCouponsByPolicyId: 정책 상태를 EXPIRED 로 바꾸고 3개 청크로 분할하여 총 2300건을 만료 처리한다")
    void expireCouponsByPolicyId_chunksUntilFinished() {
        // given
        Long policyId = 1L;
        int chunkSize = 1000;
        CouponPolicy policy = org.mockito.Mockito.mock(CouponPolicy.class);
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)).thenReturn(Optional.of(policy));

        when(chunkExecutor.expireChunk(eq(policyId), any(LocalDateTime.class), eq(chunkSize)))
                .thenReturn(1000)  // 1회차: 1,000건 처리 (풀 청크)
                .thenReturn(1000)  // 2회차: 1,000건 처리 (풀 청크)
                .thenReturn(300);   // 3회차: 300건 처리 (마지막 청크, < 1000)

        // when
        CouponPolicyExpirationResponse response =
                couponExpirationService.expireCouponsByPolicyId(policyId, chunkSize);

        // then
        assertThat(response.policyId()).isEqualTo(policyId);
        assertThat(response.affectedPolicies()).isEqualTo(1);
        assertThat(response.affectedCoupons()).isEqualTo(2300);
        verify(policy).expire();
        verify(couponPolicyCacheService).evict(policyId);
        verify(chunkExecutor, times(3)).expireChunk(eq(policyId), any(LocalDateTime.class), eq(chunkSize));
    }

    @Test
    @DisplayName("expireAllCoupons: 만료된 정책 목록을 순회하며 각 정책의 상태 및 쿠폰을 청크 처리한다")
    void expireAllCoupons_iteratesExpiredPolicies() {
        // given
        CouponPolicy p1 = createPolicy(10L);
        CouponPolicy p2 = createPolicy(20L);
        when(couponPolicyRepository.findExpiredPolicies(any(LocalDateTime.class)))
                .thenReturn(List.of(p1, p2));
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(p1));
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(20L)).thenReturn(Optional.of(p2));

        when(chunkExecutor.expireChunk(eq(10L), any(LocalDateTime.class), eq(5000))).thenReturn(200);
        when(chunkExecutor.expireChunk(eq(20L), any(LocalDateTime.class), eq(5000))).thenReturn(50);

        // when
        CouponPolicyExpirationResponse response = couponExpirationService.expireAllCoupons(5000);

        // then
        assertThat(response.affectedPolicies()).isEqualTo(2);
        assertThat(response.affectedCoupons()).isEqualTo(250);
        verify(p1).expire();
        verify(p2).expire();
        verify(couponPolicyCacheService).evict(10L);
        verify(couponPolicyCacheService).evict(20L);
    }
}
