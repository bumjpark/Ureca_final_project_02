package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.dto.response.CouponIssuanceMetricsResponse;
import com.ureca.myureca.dto.response.CouponStatusResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CouponStatusService couponStatusService;

    @BeforeEach
    void setUp() {
        couponStatusService = new CouponStatusService(couponPolicyRepository, couponIssueRepository, redisTemplate);
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
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
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
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
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
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Lua의 Fast-Fail이 정상 동작한다면 나올 수 없는 값이지만, 방어로직 자체를 검증
        when(valueOperations.get("coupon:policy:" + POLICY_ID + ":stock")).thenReturn("-5");

        CouponStatusResponse response = couponStatusService.getCouponStatus(POLICY_ID);

        assertThat(response.remainingQuantity()).isEqualTo(0);
        assertThat(response.issuedQuantity()).isEqualTo(10000);
    }

    @Test
    void 존재하지_않는_정책이면_예외가_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponStatusService.getCouponStatus(POLICY_ID))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }

    @Test
    void 발급_지표는_상태별_건수와_초당_발급수를_함께_반환한다() throws Exception {
        CouponPolicy policy = policyWithTotalQuantity(10000);
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
        when(couponIssueRepository.countByStatusGroupedForPolicy(POLICY_ID)).thenReturn(List.of(
                new Object[]{IssueStatus.ISSUED, 100L},
                new Object[]{IssueStatus.USED, 30L},
                new Object[]{IssueStatus.EXPIRED, 5L}
        ));
        when(couponIssueRepository.countIssuedSince(org.mockito.ArgumentMatchers.eq(POLICY_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(7L);
        when(couponIssueRepository.countIssuedBySecondSince(org.mockito.ArgumentMatchers.eq(POLICY_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        CouponIssuanceMetricsResponse response = couponStatusService.getIssuanceMetrics(POLICY_ID, 10);

        assertThat(response.usedCount()).isEqualTo(30L);
        assertThat(response.expiredCount()).isEqualTo(5L);
        assertThat(response.totalIssuedEver()).isEqualTo(135L);
        assertThat(response.issuedLastSecond()).isEqualTo(7L);
        // 창(10초) + 현재 초까지 최소 11개의 버킷이 0으로라도 채워져 있어야 그래프에 빈 구간이 안 생긴다
        assertThat(response.timeline().size()).isGreaterThanOrEqualTo(11);
        assertThat(response.timeline()).allSatisfy(point -> assertThat(point.count()).isEqualTo(0L));
    }

    @Test
    void 그래프_창의_오른쪽_끝은_지금이_아니라_마지막_발급_시각으로_고정된다() throws Exception {
        CouponPolicy policy = policyWithTotalQuantity(10000);
        // 임의의 과거 시각(나노초 없음) — "지금"으로 창을 잡았다면 이 값이 나올 수 없다.
        LocalDateTime lastIssuedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 5);
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
        when(couponIssueRepository.countByStatusGroupedForPolicy(POLICY_ID)).thenReturn(List.of());
        when(couponIssueRepository.countIssuedSince(org.mockito.ArgumentMatchers.eq(POLICY_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0L);
        when(couponIssueRepository.countIssuedBySecondSince(org.mockito.ArgumentMatchers.eq(POLICY_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(couponIssueRepository.findMaxIssuedAtByCouponPolicyId(POLICY_ID)).thenReturn(lastIssuedAt);

        CouponIssuanceMetricsResponse response = couponStatusService.getIssuanceMetrics(POLICY_ID, 5);

        String lastBucket = response.timeline().get(response.timeline().size() - 1).bucket();
        assertThat(lastBucket).isEqualTo("2026-01-01 10:00:05");
        assertThat(response.timeline()).hasSize(6); // seconds=5 → since부터 end까지 6개(5+1)
    }

    @Test
    void 발급_이력이_없으면_그래프_창은_지금_시각까지_채워진다() throws Exception {
        CouponPolicy policy = policyWithTotalQuantity(10000);
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.of(policy));
        when(couponIssueRepository.countByStatusGroupedForPolicy(POLICY_ID)).thenReturn(List.of());
        when(couponIssueRepository.countIssuedSince(org.mockito.ArgumentMatchers.eq(POLICY_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0L);
        when(couponIssueRepository.countIssuedBySecondSince(org.mockito.ArgumentMatchers.eq(POLICY_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(couponIssueRepository.findMaxIssuedAtByCouponPolicyId(POLICY_ID)).thenReturn(null);

        LocalDateTime before = LocalDateTime.now().withNano(0);
        CouponIssuanceMetricsResponse response = couponStatusService.getIssuanceMetrics(POLICY_ID, 5);
        LocalDateTime after = LocalDateTime.now().withNano(0);

        String lastBucket = response.timeline().get(response.timeline().size() - 1).bucket();
        LocalDateTime lastBucketTime = LocalDateTime.parse(lastBucket, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertThat(lastBucketTime).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    void 발급_지표_조회시_존재하지_않는_정책이면_예외가_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponStatusService.getIssuanceMetrics(POLICY_ID, 30))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }
}
