package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PENDING 리포트를 실제로 대사(비교)해서 완료 처리하는 로직 테스트.
 * 비교 대상 정의(ISSUED 기준)와 초과발급 체크는 Docs/Verification-Batch-Design.md 2, 7번 참고.
 */
@ExtendWith(MockitoExtension.class)
class VerificationAsyncTriggerTest {

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private VerificationReportRepository verificationReportRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private VerificationAsyncTrigger.MismatchReportWriter mismatchReportWriter;

    private VerificationAsyncTrigger asyncTrigger;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        asyncTrigger = new VerificationAsyncTrigger(
                couponIssueRepository, verificationReportRepository, redisTemplate, mismatchReportWriter
        );
    }

    private CouponPolicy policy(long id, int totalQuantity) {
        CouponPolicy policy = new CouponPolicy(
                "테스트 정책", CouponType.FIXED, 1000, totalQuantity,
                LocalDateTime.now().plusDays(1), null
        );
        ReflectionTestUtils.setField(policy, "id", id);
        return policy;
    }

    private VerificationReport pendingReport(CouponPolicy policy) {
        VerificationReport report = VerificationReport.pending(policy, LocalDateTime.now());
        ReflectionTestUtils.setField(report, "id", 1L);
        return report;
    }

    @Test
    void 완전히_일치하면_SUCCESS로_완료된다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200", "300"));
        when(zSetOperations.size(any())).thenReturn(0L);

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(report.getMismatchCount()).isZero();
        assertThat(report.getTotalIssued()).isEqualTo(3);
        assertThat(report.getReportUrl()).isNull();
        verify(mismatchReportWriter, never()).write(any(), any(), any(), any(), anyInt());
    }

    @Test
    void Redis에만_있는_유저가_있으면_MISMATCH_FOUND이고_CSV를_생성한다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200", "999"));
        when(zSetOperations.size(any())).thenReturn(0L);
        Path expectedCsvPath = Path.of("reports", "verification-1.csv");
        when(mismatchReportWriter.write(eq(1L), any(), any(), any(), anyInt())).thenReturn(expectedCsvPath);

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(1);
        assertThat(report.getReportUrl()).isEqualTo(expectedCsvPath.toString());
        verify(mismatchReportWriter).write(
                eq(1L), any(), eq(Set.of(100L, 200L)), eq(Set.of(100L, 200L, 999L)), eq(0));
    }

    @Test
    void DB에만_있는_유저가_있으면_MISMATCH_FOUND이다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(mismatchReportWriter.write(any(), any(), any(), any(), anyInt())).thenReturn(Path.of("reports/x.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(1);
    }

    @Test
    void RESERVED가_남아있으면_totalReserved에만_반영되고_비교대상은_아니다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.members(any())).thenReturn(Set.of("100"));
        when(zSetOperations.size(any())).thenReturn(5L);

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(report.getMismatchCount()).isZero();
        assertThat(report.getTotalReserved()).isEqualTo(5);
    }

    @Test
    void DB와_Redis가_일치해도_재고를_초과했으면_MISMATCH_FOUND이고_CSV를_남긴다() {
        CouponPolicy policy = policy(1L, 2); // 재고 2장인데
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L)); // 3명 발급
        when(setOperations.members(any())).thenReturn(Set.of("100", "200", "300")); // Redis도 3명 — diff는 0
        when(zSetOperations.size(any())).thenReturn(0L);
        when(mismatchReportWriter.write(any(), any(), any(), any(), anyInt()))
                .thenReturn(Path.of("reports", "oversold.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getTotalIssued()).isEqualTo(3);
        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(1); // 초과분 1건
        assertThat(report.getReportUrl()).isNotNull();
        verify(mismatchReportWriter).write(eq(1L), any(), any(), any(), eq(1));
    }

    @Test
    void 리포트가_없으면_예외가_발생하고_execute는_이를_잡아서_로그만_남긴다() {
        when(verificationReportRepository.findById(999L)).thenReturn(Optional.empty());

        // execute()는 예외를 던지지 않고 내부에서 잡아 로그만 남긴다 (백그라운드 스레드라 던져봐야 받을 곳이 없음)
        asyncTrigger.execute(999L);
    }
}
