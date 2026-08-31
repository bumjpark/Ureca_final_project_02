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
import com.ureca.myureca.dto.response.VerificationMismatchRowResponse;
import com.ureca.myureca.dto.response.VerificationReportResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.VerificationNotAllowedException;
import com.ureca.myureca.exception.VerificationReportCsvNotAvailableException;
import com.ureca.myureca.exception.VerificationReportFileMissingException;
import com.ureca.myureca.exception.VerificationReportNotFoundException;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    @Mock
    private VerificationAsyncTrigger.MismatchReportWriter mismatchReportWriter;

    @TempDir
    Path tempDir;

    private VerificationService verificationService;

    @BeforeEach
    void setUp() {
        verificationService = new VerificationService(
                couponPolicyRepository, verificationReportRepository, redisTemplate, asyncTrigger,
                mismatchReportWriter
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

    /**
     * PENDING 리포트 하나가 그 정책의 검증을 영구히 봉쇄하는 것을 막는 탈출구. 검증 도중
     * 애플리케이션이 죽으면 FAILED를 남길 주체가 사라져 리포트가 영원히 PENDING으로 남는데,
     * 시간 기준 판정이 없으면 수동 DB 조작 없이는 그 정책을 다시 검증할 수 없다.
     */
    @Test
    void 오래_방치된_PENDING_리포트는_FAILED로_정리하고_새_검증을_접수한다() {
        CouponPolicy target = policy(1L);
        VerificationReport zombie = VerificationReport.pending(target, LocalDateTime.now().minusHours(3));
        ReflectionTestUtils.setField(zombie, "id", 777L);

        when(couponPolicyRepository.findByDeletedAtIsNull()).thenReturn(List.of(target));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:policy:1:stock")).thenReturn("0");
        when(verificationReportRepository.findFirstByCouponPolicy_IdAndStatus(1L, VerificationStatus.PENDING))
                .thenReturn(java.util.Optional.of(zombie));
        when(verificationReportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<VerificationReportResponse> responses = verificationService.runVerification(1L);

        assertThat(zombie.getStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).status()).isEqualTo(VerificationStatus.PENDING);
        verify(asyncTrigger).execute(any());
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

    @Test
    void 필터_없이_조회하면_findAllByOrderByRunAtDesc를_쓴다() {
        Pageable pageable = PageRequest.of(0, 10);
        VerificationReport report = completedReport(1L, 100L);
        when(verificationReportRepository.findAllByOrderByRunAtDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(report), pageable, 1));

        PageResponse<VerificationReportResponse> result =
                verificationService.getVerificationReports(null, null, pageable);

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        verify(verificationReportRepository).findAllByOrderByRunAtDesc(pageable);
    }

    @Test
    void policyId만_있으면_해당_정책_기준으로_조회한다() {
        Pageable pageable = PageRequest.of(0, 10);
        VerificationReport report = completedReport(5L, 100L);
        when(verificationReportRepository.findByCouponPolicy_IdOrderByRunAtDesc(5L, pageable))
                .thenReturn(new PageImpl<>(List.of(report), pageable, 1));

        PageResponse<VerificationReportResponse> result =
                verificationService.getVerificationReports(5L, null, pageable);

        assertThat(result.content()).extracting(VerificationReportResponse::policyId).containsExactly(5L);
        verify(verificationReportRepository).findByCouponPolicy_IdOrderByRunAtDesc(5L, pageable);
    }

    @Test
    void status만_있으면_해당_상태_기준으로_조회한다() {
        Pageable pageable = PageRequest.of(0, 10);
        when(verificationReportRepository.findByStatusOrderByRunAtDesc(VerificationStatus.MISMATCH_FOUND, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        verificationService.getVerificationReports(null, VerificationStatus.MISMATCH_FOUND, pageable);

        verify(verificationReportRepository)
                .findByStatusOrderByRunAtDesc(VerificationStatus.MISMATCH_FOUND, pageable);
    }

    @Test
    void policyId와_status가_둘_다_있으면_둘_다_적용해서_조회한다() {
        Pageable pageable = PageRequest.of(0, 10);
        when(verificationReportRepository.findByCouponPolicy_IdAndStatusOrderByRunAtDesc(
                5L, VerificationStatus.SUCCESS, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        verificationService.getVerificationReports(5L, VerificationStatus.SUCCESS, pageable);

        verify(verificationReportRepository)
                .findByCouponPolicy_IdAndStatusOrderByRunAtDesc(5L, VerificationStatus.SUCCESS, pageable);
    }

    @Test
    void 상세_조회에_성공하면_DTO를_반환한다() {
        VerificationReport report = completedReport(1L, 100L);
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        VerificationReportResponse response = verificationService.getVerificationReport(100L);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.policyId()).isEqualTo(1L);
    }

    @Test
    void 존재하지_않는_리포트_id면_상세_조회에_예외가_발생한다() {
        when(verificationReportRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.getVerificationReport(999L))
                .isInstanceOf(VerificationReportNotFoundException.class);
    }

    @Test
    void 존재하지_않는_리포트_id면_CSV_다운로드에도_예외가_발생한다() {
        when(verificationReportRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.getVerificationReportCsv(999L))
                .isInstanceOf(VerificationReportNotFoundException.class);
    }

    @Test
    void PENDING_리포트는_CSV_다운로드가_불가능하다() {
        VerificationReport report = VerificationReport.pending(policy(1L), LocalDateTime.now());
        ReflectionTestUtils.setField(report, "id", 100L);
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> verificationService.getVerificationReportCsv(100L))
                .isInstanceOf(VerificationReportCsvNotAvailableException.class)
                .satisfies(e -> assertThat(((VerificationReportCsvNotAvailableException) e).getStatus())
                        .isEqualTo(VerificationStatus.PENDING));
    }

    @Test
    void SUCCESS_리포트는_CSV_다운로드가_불가능하다() {
        VerificationReport report = completedReport(1L, 100L); // SUCCESS, reportUrl 없음
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> verificationService.getVerificationReportCsv(100L))
                .isInstanceOf(VerificationReportCsvNotAvailableException.class)
                .satisfies(e -> assertThat(((VerificationReportCsvNotAvailableException) e).getStatus())
                        .isEqualTo(VerificationStatus.SUCCESS));
    }

    @Test
    void FAILED_리포트는_CSV_다운로드가_불가능하다() {
        VerificationReport report = VerificationReport.pending(policy(1L), LocalDateTime.now());
        report.fail("Redis 연결 실패 시뮬레이션");
        ReflectionTestUtils.setField(report, "id", 100L);
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> verificationService.getVerificationReportCsv(100L))
                .isInstanceOf(VerificationReportCsvNotAvailableException.class)
                .satisfies(e -> assertThat(((VerificationReportCsvNotAvailableException) e).getStatus())
                        .isEqualTo(VerificationStatus.FAILED));
    }

    @Test
    void 실제_CSV_파일이_있으면_다운로드용_리소스를_반환한다() throws Exception {
        VerificationAsyncTrigger.MismatchReportWriter realWriter =
                new VerificationAsyncTrigger.MismatchReportWriter(tempDir.toString());
        VerificationService service = new VerificationService(
                couponPolicyRepository, verificationReportRepository, redisTemplate, asyncTrigger, realWriter);

        Path csvFile = tempDir.resolve("verification-1-123.csv");
        Files.writeString(csvFile, "policyId,userId,couponIssueId,discrepancyType,detectedAt\n");

        VerificationReport report = new VerificationReport(
                policy(1L), LocalDateTime.now(), 3, 0, 1, VerificationStatus.MISMATCH_FOUND
        );
        report.attachReportUrl(csvFile.toString());
        ReflectionTestUtils.setField(report, "id", 100L);
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        VerificationService.ReportCsvFile result = service.getVerificationReportCsv(100L);

        assertThat(result.resource().exists()).isTrue();
        assertThat(result.filename()).isEqualTo("verification-report-1-100.csv");
    }

    @Test
    void 저장된_경로의_파일이_실제로_없으면_예외가_발생한다() {
        VerificationAsyncTrigger.MismatchReportWriter realWriter =
                new VerificationAsyncTrigger.MismatchReportWriter(tempDir.toString());
        VerificationService service = new VerificationService(
                couponPolicyRepository, verificationReportRepository, redisTemplate, asyncTrigger, realWriter);

        VerificationReport report = new VerificationReport(
                policy(1L), LocalDateTime.now(), 3, 0, 1, VerificationStatus.MISMATCH_FOUND
        );
        report.attachReportUrl(tempDir.resolve("never-written.csv").toString()); // 실제로는 안 씀
        ReflectionTestUtils.setField(report, "id", 100L);
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.getVerificationReportCsv(100L))
                .isInstanceOf(VerificationReportFileMissingException.class);
    }

    @Test
    void 저장된_경로가_reportDir_밖이면_예외가_발생한다() {
        VerificationAsyncTrigger.MismatchReportWriter realWriter =
                new VerificationAsyncTrigger.MismatchReportWriter(tempDir.toString());
        VerificationService service = new VerificationService(
                couponPolicyRepository, verificationReportRepository, redisTemplate, asyncTrigger, realWriter);

        VerificationReport report = new VerificationReport(
                policy(1L), LocalDateTime.now(), 3, 0, 1, VerificationStatus.MISMATCH_FOUND
        );
        report.attachReportUrl(tempDir.resolveSibling("outside.csv").toString()); // reportDir 밖
        ReflectionTestUtils.setField(report, "id", 100L);
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.getVerificationReportCsv(100L))
                .isInstanceOf(VerificationReportFileMissingException.class);
    }

    @Test
    void 불일치_CSV를_파싱해_행_목록으로_돌려준다() throws Exception {
        VerificationAsyncTrigger.MismatchReportWriter realWriter =
                new VerificationAsyncTrigger.MismatchReportWriter(tempDir.toString());
        VerificationService service = new VerificationService(
                couponPolicyRepository, verificationReportRepository, redisTemplate, asyncTrigger, realWriter);

        Path csvFile = tempDir.resolve("verification-1-mismatch.csv");
        Files.writeString(csvFile,
                "policyId,userId,couponIssueId,discrepancyType,detectedAt\n"
                        + "1,10,,REDIS_ONLY,2026-08-29T12:00:00\n"
                        + "1,,,OVERSOLD(+3),2026-08-29T12:00:00\n"
                        + "1,20,55,HISTORY_MISMATCH,2026-08-29T12:00:00\n");

        VerificationReport report = new VerificationReport(
                policy(1L), LocalDateTime.now(), 3, 0, 3, VerificationStatus.MISMATCH_FOUND
        );
        report.attachReportUrl(csvFile.toString());
        ReflectionTestUtils.setField(report, "id", 100L);
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        PageResponse<VerificationMismatchRowResponse> result =
                service.getVerificationReportMismatches(100L, PageRequest.of(0, 20));

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.content()).hasSize(3);
        assertThat(result.content().get(0).userId()).isEqualTo(10L);
        assertThat(result.content().get(0).couponIssueId()).isNull();
        assertThat(result.content().get(0).discrepancyType()).isEqualTo("REDIS_ONLY");
        // 정책 단위 요약 행(OVERSOLD)은 userId/couponIssueId가 둘 다 비어있다
        assertThat(result.content().get(1).userId()).isNull();
        assertThat(result.content().get(1).discrepancyType()).isEqualTo("OVERSOLD(+3)");
        assertThat(result.content().get(2).userId()).isEqualTo(20L);
        assertThat(result.content().get(2).couponIssueId()).isEqualTo(55L);
    }

    @Test
    void 불일치_조회는_요청한_페이지_크기만큼만_잘라서_돌려준다() throws Exception {
        VerificationAsyncTrigger.MismatchReportWriter realWriter =
                new VerificationAsyncTrigger.MismatchReportWriter(tempDir.toString());
        VerificationService service = new VerificationService(
                couponPolicyRepository, verificationReportRepository, redisTemplate, asyncTrigger, realWriter);

        StringBuilder csv = new StringBuilder("policyId,userId,couponIssueId,discrepancyType,detectedAt\n");
        for (long userId = 1; userId <= 5; userId++) {
            csv.append("1,").append(userId).append(",,REDIS_ONLY,2026-08-29T12:00:00\n");
        }
        Path csvFile = tempDir.resolve("verification-1-paged.csv");
        Files.writeString(csvFile, csv.toString());

        VerificationReport report = new VerificationReport(
                policy(1L), LocalDateTime.now(), 5, 0, 5, VerificationStatus.MISMATCH_FOUND
        );
        report.attachReportUrl(csvFile.toString());
        ReflectionTestUtils.setField(report, "id", 100L);
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        PageResponse<VerificationMismatchRowResponse> firstPage =
                service.getVerificationReportMismatches(100L, PageRequest.of(0, 2));
        PageResponse<VerificationMismatchRowResponse> secondPage =
                service.getVerificationReportMismatches(100L, PageRequest.of(1, 2));

        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.content()).extracting(VerificationMismatchRowResponse::userId).containsExactly(1L, 2L);
        assertThat(secondPage.content()).extracting(VerificationMismatchRowResponse::userId).containsExactly(3L, 4L);
    }

    @Test
    void reportUrl이_없으면_불일치_조회도_CSV_다운로드와_같은_예외를_던진다() {
        VerificationReport report = completedReport(1L, 100L); // SUCCESS, reportUrl 없음
        when(verificationReportRepository.findById(100L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> verificationService.getVerificationReportMismatches(100L, PageRequest.of(0, 20)))
                .isInstanceOf(VerificationReportCsvNotAvailableException.class);
    }

    @Test
    void 존재하지_않는_리포트_id면_불일치_조회에도_예외가_발생한다() {
        when(verificationReportRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.getVerificationReportMismatches(999L, PageRequest.of(0, 20)))
                .isInstanceOf(VerificationReportNotFoundException.class);
    }

    private VerificationReport completedReport(long policyId, long reportId) {
        CouponPolicy p = policy(policyId);
        VerificationReport report = new VerificationReport(
                p, LocalDateTime.now(), 3, 0, 0, VerificationStatus.SUCCESS
        );
        ReflectionTestUtils.setField(report, "id", reportId);
        return report;
    }
}
