package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CouponExpirationServiceTest {

    @Mock
    private CouponPolicyRepository couponPolicyRepository;

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
    @DisplayName("expireCouponsByPolicyId: 3개 청크(1000, 1000, 300)로 분할되어 총 2300건이 처리된다")
    void expireCouponsByPolicyId_chunksUntilFinished() {
        // given
        Long policyId = 1L;
        int chunkSize = 1000;
        when(chunkExecutor.expireChunk(eq(policyId), any(LocalDateTime.class), eq(chunkSize)))
                .thenReturn(1000)  // 1회차: 1,000건 처리 (풀 청크)
                .thenReturn(1000)  // 2회차: 1,000건 처리 (풀 청크)
                .thenReturn(300);   // 3회차: 300건 처리 (마지막 청크, < 1000)

        // when
        int total = couponExpirationService.expireCouponsByPolicyId(policyId, chunkSize);

        // then
        assertThat(total).isEqualTo(2300);
        verify(chunkExecutor, times(3)).expireChunk(eq(policyId), any(LocalDateTime.class), eq(chunkSize));
    }

    @Test
    @DisplayName("expireAllCoupons: 만료된 정책 목록을 순회하며 각 정책의 쿠폰을 청크 처리한다")
    void expireAllCoupons_iteratesExpiredPolicies() {
        // given
        CouponPolicy p1 = createPolicy(10L);
        CouponPolicy p2 = createPolicy(20L);
        when(couponPolicyRepository.findExpiredPolicies(any(LocalDateTime.class)))
                .thenReturn(List.of(p1, p2));

        when(chunkExecutor.expireChunk(eq(10L), any(LocalDateTime.class), eq(5000))).thenReturn(200);
        when(chunkExecutor.expireChunk(eq(20L), any(LocalDateTime.class), eq(5000))).thenReturn(50);

        // when
        int total = couponExpirationService.expireAllCoupons(5000);

        // then
        assertThat(total).isEqualTo(250);
        verify(chunkExecutor).expireChunk(eq(10L), any(LocalDateTime.class), eq(5000));
        verify(chunkExecutor).expireChunk(eq(20L), any(LocalDateTime.class), eq(5000));
    }
}
