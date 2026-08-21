package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.dto.response.VerificationReportResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.VerificationNotAllowedException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 오케스트레이터 테스트. 실제 대사(비교) 로직은 {@link VerificationAsyncTriggerTest} 참고 —
 * 여기서는 접근 조건(재고 0), policyId 유무에 따른 단건/전체 정책 대상 분기, PENDING 저장 +
 * 비동기 트리거 호출만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    @Mock
    private CouponPolicyRepository couponPolicyRepository;

    @Mock
    private VerificationReportRepository verificationReportRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private VerificationAsyncTrigger asyncTrigger;

    private VerificationService verificationService;

    @BeforeEach
    void setUp() {
        verificationService = new VerificationService(
                couponPolicyRepository, verificationReportRepository, redisTemplate, asyncTrigger
        );
    }

    private CouponPolicy policy(long id) {
        CouponPolicy policy = new CouponPolicy(
                "테스트 정책", CouponType.FIXED, 1000, 10000,
                LocalDateTime.now().plusDays(1), null
        );
        ReflectionTestUtils.setField(policy, "id", id);
        return policy;
    }

    private void stubSave() {
        when(verificationReportRepository.save(any(VerificationReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 다른_정책이라도_재고가_남아있으면_예외가_발생한다() {
        CouponPolicy live = policy(1L);
        CouponPolicy target = policy(2L);
        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of(live, target));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:1:stock")).thenReturn("50"); // 다른 정책이 재고 남음

        assertThatThrownBy(() -> verificationService.runVerification(2L))
                .isInstanceOf(VerificationNotAllowedException.class);

        verify(asyncTrigger, never()).execute(anyLong());
        verify(verificationReportRepository, never()).save(any());
    }

    @Test
    void 재고_키가_없으면_0으로_취급해_통과한다() {
        CouponPolicy target = policy(1L);
        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of(target));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:1:stock")).thenReturn(null); // 키 없음 = Lua Fast-Fail과 동일 취급
        stubSave();

        List<VerificationReportResponse> responses = verificationService.runVerification(1L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).status()).isEqualTo(VerificationStatus.PENDING);
        verify(asyncTrigger).execute(any());
    }

    @Test
    void policyId를_지정하면_그_정책만_PENDING으로_접수한다() {
        CouponPolicy target = policy(5L);
        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of(target));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:5:stock")).thenReturn("0");
        when(verificationReportRepository.save(any(VerificationReport.class)))
                .thenAnswer(invocation -> {
                    VerificationReport saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 100L);
                    return saved;
                });

        List<VerificationReportResponse> responses = verificationService.runVerification(5L);

        assertThat(responses).hasSize(1);
        VerificationReportResponse response = responses.get(0);
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.policyId()).isEqualTo(5L);
        assertThat(response.status()).isEqualTo(VerificationStatus.PENDING);
        verify(asyncTrigger).execute(100L);
    }

    @Test
    void policyId가_없으면_전체_정책을_대상으로_각각_PENDING_접수한다() {
        CouponPolicy p1 = policy(1L);
        CouponPolicy p2 = policy(2L);
        CouponPolicy p3 = policy(3L);
        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of(p1, p2, p3));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:1:stock")).thenReturn("0");
        when(valueOperations.get("coupon:policy:2:stock")).thenReturn(null);
        when(valueOperations.get("coupon:policy:3:stock")).thenReturn("0");
        stubSave();

        List<VerificationReportResponse> responses = verificationService.runVerification(null);

        assertThat(responses).hasSize(3);
        assertThat(responses).extracting(VerificationReportResponse::policyId)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(responses).allMatch(r -> r.status() == VerificationStatus.PENDING);
        verify(asyncTrigger, times(3)).execute(any());
    }

    @Test
    void 정책이_하나도_없으면_빈_리스트를_반환한다() {
        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of());

        List<VerificationReportResponse> responses = verificationService.runVerification(null);

        assertThat(responses).isEmpty();
        verify(asyncTrigger, never()).execute(anyLong());
    }

    @Test
    void 존재하지_않는_policyId면_예외가_발생한다() {
        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of());

        assertThatThrownBy(() -> verificationService.runVerification(999L))
                .isInstanceOf(CouponPolicyNotFoundException.class);

        verify(asyncTrigger, never()).execute(anyLong());
    }

    @Test
    void 이미_PENDING인_정책이면_새로_만들지_않고_기존_리포트를_반환한다() {
        CouponPolicy target = policy(1L);
        VerificationReport existingPending = VerificationReport.pending(target, LocalDateTime.now());
        ReflectionTestUtils.setField(existingPending, "id", 777L);

        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of(target));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:1:stock")).thenReturn("0");
        when(verificationReportRepository.findFirstByCouponPolicy_IdAndStatus(1L, VerificationStatus.PENDING))
                .thenReturn(java.util.Optional.of(existingPending));

        List<VerificationReportResponse> responses = verificationService.runVerification(1L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo(777L);
        verify(verificationReportRepository, never()).save(any());
        verify(asyncTrigger, never()).execute(anyLong());
    }

    @Test
    void 순회_중_실패하면_이미_접수된_정책_목록을_담아_예외를_던진다() {
        CouponPolicy p1 = policy(1L);
        CouponPolicy p2 = policy(2L);
        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of(p1, p2));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:1:stock")).thenReturn("0");
        when(valueOperations.get("coupon:policy:2:stock")).thenReturn("0");
        when(verificationReportRepository.findFirstByCouponPolicy_IdAndStatus(any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(verificationReportRepository.save(any(VerificationReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0)) // p1은 성공
                .thenThrow(new RuntimeException("DB 순단")); // p2에서 실패

        assertThatThrownBy(() -> verificationService.runVerification(null))
                .isInstanceOf(com.ureca.myureca.exception.VerificationDispatchException.class)
                .satisfies(e -> {
                    var dispatchException = (com.ureca.myureca.exception.VerificationDispatchException) e;
                    assertThat(dispatchException.getDispatchedPolicyIds()).containsExactly(1L);
                });

        verify(asyncTrigger, times(1)).execute(any()); // p1만 디스패치됨
    }
}
