package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CouponPolicyCacheServiceTest {

    @Mock
    private CouponPolicyRepository couponPolicyRepository;

    @InjectMocks
    private CouponPolicyCacheService cacheService;

    private final Long POLICY_ID = 1L;
    private CouponPolicy testPolicy;

    @BeforeEach
    void setUp() {
        testPolicy = new CouponPolicy(
                "테스트 쿠폰", CouponType.FIXED, 1000, 10000,
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1)
        );
        ReflectionTestUtils.setField(testPolicy, "id", POLICY_ID);
    }

    @Test
    void 캐시_미스_시_DB를_조회하고_결과를_캐싱한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(testPolicy));

        CouponPolicyCacheService.CachedPolicy cached = cacheService.getPolicy(POLICY_ID);

        assertThat(cached.id()).isEqualTo(POLICY_ID);
        assertThat(cached.openAt()).isEqualTo(testPolicy.getOpenAt());
        verify(couponPolicyRepository, times(1)).findByIdAndDeletedAtIsNull(POLICY_ID);
    }

    @Test
    void 캐시_히트_시_DB를_다시_조회하지_않는다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(testPolicy));

        // 100번 연속 조회
        for (int i = 0; i < 100; i++) {
            cacheService.getPolicy(POLICY_ID);
        }

        // DB 쿼리는 단 1번만 실행되어 HikariCP 풀을 완벽 보호
        verify(couponPolicyRepository, times(1)).findByIdAndDeletedAtIsNull(POLICY_ID);
    }

    @Test
    void evict_호출_시_캐시가_제거되어_다음_조회_시_DB를_다시_조회한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(testPolicy));

        cacheService.getPolicy(POLICY_ID); // 1차 조회 (Cache Miss -> DB 조회)
        cacheService.evict(POLICY_ID);      // 정책 수정/삭제로 캐시 무효화
        cacheService.getPolicy(POLICY_ID); // 2차 조회 (Cache Miss -> DB 재조회)

        verify(couponPolicyRepository, times(2)).findByIdAndDeletedAtIsNull(POLICY_ID);
    }

    @Test
    void 존재하지_않는_정책_조회_시_CouponPolicyNotFoundException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> cacheService.getPolicy(999L))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }

    // ─────────────────────────────────────────────────
    // 이슈 #14 — negative caching
    // ─────────────────────────────────────────────────

    @Test
    void 존재하지_않는_정책을_반복_조회해도_DB는_한_번만_조회한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(999L))
                .thenReturn(Optional.empty());

        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> cacheService.getPolicy(999L))
                    .isInstanceOf(CouponPolicyNotFoundException.class);
        }

        // 오타/스캐닝성 반복 요청이 매번 DB로 직행하지 않고 negative cache로 막힌다
        verify(couponPolicyRepository, times(1)).findByIdAndDeletedAtIsNull(999L);
    }

    @Test
    void evict_호출_시_negative_cache도_함께_제거되어_다음_조회는_DB를_다시_본다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(999L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(testPolicy));

        assertThatThrownBy(() -> cacheService.getPolicy(999L))
                .isInstanceOf(CouponPolicyNotFoundException.class);

        cacheService.evict(999L); // 예: 방금 이 id로 정책이 새로 생성된 경우

        CouponPolicyCacheService.CachedPolicy result = cacheService.getPolicy(999L);
        assertThat(result).isNotNull();
        verify(couponPolicyRepository, times(2)).findByIdAndDeletedAtIsNull(999L);
    }
}
