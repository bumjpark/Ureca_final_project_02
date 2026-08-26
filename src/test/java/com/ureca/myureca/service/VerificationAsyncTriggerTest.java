package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponHistoryStatusSnapshot;
import com.ureca.myureca.repository.CouponIssueLifecycleSnapshot;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.QueueJoinLogRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import com.ureca.myureca.service.VerificationAsyncTrigger.LifecycleAnomaly;
import com.ureca.myureca.service.VerificationAsyncTrigger.MismatchFindings;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PENDING 리포트를 비교해서 완료 처리하는 로직 테스트.
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
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CouponHistoryRepository couponHistoryRepository;

    @Mock
    private VerificationAsyncTrigger.MismatchReportWriter mismatchReportWriter;

    @Mock
    private QueueJoinLogRepository queueJoinLogRepository;

    private VerificationAsyncTrigger asyncTrigger;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 재고 누수/생명주기 체크와 무관한 테스트는 "이상 없음" 기본값(재고 스텁 안 함=0, 이력 없음)으로 흐른다.
        org.mockito.Mockito.lenient()
                .when(couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient()
                .when(couponHistoryRepository.findStatusSnapshotsByCouponPolicyId(any())).thenReturn(List.of());
        // queue_join_log가 비어있다고 가정(컨슈머 미구현) — 대부분의 기존 테스트는 FCFS 체크와 무관하므로
        // 기본(구간 적재분=0 < liveN)으로 건너뛴다. FCFS 체크 자체를 검증하는 테스트는 개별적으로 덮어쓴다.
        org.mockito.Mockito.lenient()
                .when(queueJoinLogRepository.countByCouponPolicyIdAndQueueRankLessThanEqual(any(), any()))
                .thenReturn(0L);

        asyncTrigger = new VerificationAsyncTrigger(
                couponIssueRepository, verificationReportRepository, redisTemplate, mismatchReportWriter,
                couponHistoryRepository, queueJoinLogRepository
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
        // totalReservedEver(=10000-stock)이 dbIssued(3)+reserved(0)와 같아야 누수 0건 — stock=9997
        when(valueOperations.get(any())).thenReturn("9997");

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(report.getMismatchCount()).isZero();
        assertThat(report.getTotalIssued()).isEqualTo(3);
        assertThat(report.getReportUrl()).isNull();
        verify(mismatchReportWriter, never()).write(any(), any(), any());
    }

    @Test
    void queue_join_log가_비어있으면_FCFS_체크를_건너뛰고_오탐하지_않는다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200", "300"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(valueOperations.get(any())).thenReturn("9997");
        // 구간 [1,3] 적재분=0(기본값) < liveN(3) — queue-join-events 컨슈머 미가동 상황을 시뮬레이션

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(report.getMismatchCount()).isZero();
        verify(queueJoinLogRepository, never()).findUserIdsOrderByQueueRankAsc(any(), any());
        verify(mismatchReportWriter, never()).write(any(), any(), any());
    }

    @Test
    void 순번_구간이_liveN만큼_안_찼으면_캐치업_중으로_보고_FCFS_체크를_건너뛴다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200", "300"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(valueOperations.get(any())).thenReturn("9997");
        // (rank<=liveN) 확인
        when(queueJoinLogRepository.countByCouponPolicyIdAndQueueRankLessThanEqual(1L, 3L)).thenReturn(2L);

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(report.getMismatchCount()).isZero();
        verify(queueJoinLogRepository, never()).findUserIdsOrderByQueueRankAsc(any(), any());
    }

    @Test
    void 도착순위_밖에서_발급됐으면_FCFS_MISMATCH_FOUND이고_CSV에_반영된다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        // DB 발급자는 100,200,999 인데
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 999L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200", "999"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(valueOperations.get(any())).thenReturn("9997");
        // 대기열 도착순 상위 3명(liveN=min(3,10000)=3)은 100,200,300 이었다 -> 999는 순번 밖, 300은 도착순인데 못 받음
        when(queueJoinLogRepository.countByCouponPolicyIdAndQueueRankLessThanEqual(1L, 3L)).thenReturn(3L); // 구간 [1,3] 완전 적재
        when(queueJoinLogRepository.findUserIdsOrderByQueueRankAsc(eq(1L), eq(org.springframework.data.domain.PageRequest.of(0, 3))))
                .thenReturn(List.of(100L, 200L, 300L));
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports", "fcfs.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(2); // (300 못받음, 999 잘못받음) 쌍
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(100L, 200L, 999L), Set.of(100L, 200L, 999L), 0, 0, List.of(),
                        Set.of(100L, 200L, 300L))));
    }

    @Test
    void Redis에만_있는_유저가_있으면_MISMATCH_FOUND이고_CSV를_생성한다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200", "999"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(valueOperations.get(any())).thenReturn("9998"); // dbIssued(2)+reserved(0) 기준 누수 0건
        Path expectedCsvPath = Path.of("reports", "verification-1.csv");
        when(mismatchReportWriter.write(eq(1L), any(), any())).thenReturn(expectedCsvPath);

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(1);
        assertThat(report.getReportUrl()).isEqualTo(expectedCsvPath.toString());
        verify(mismatchReportWriter).write(
                eq(1L), any(),
                eq(new MismatchFindings(Set.of(100L, 200L), Set.of(100L, 200L, 999L), 0, 0, List.of(), Set.of())));
    }

    @Test
    void DB에만_있는_유저가_있으면_MISMATCH_FOUND이다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(valueOperations.get(any())).thenReturn("9997");
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports/x.csv"));

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
        when(valueOperations.get(any())).thenReturn("9994"); // dbIssued(1)+reserved(5) 기준 누수 0건

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
        // stock 스텁 안 함(키 없음) -> Check A는 건너뛰고 stockLeakCount=0
        when(mismatchReportWriter.write(any(), any(), any()))
                .thenReturn(Path.of("reports", "oversold.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getTotalIssued()).isEqualTo(3);
        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(1); // 초과분 1건 (누수는 음수라 0건)
        assertThat(report.getReportUrl()).isNotNull();
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(100L, 200L, 300L), Set.of(100L, 200L, 300L), 1, 0, List.of(), Set.of())));
    }

    @Test
    void 재고_누수가_있으면_MISMATCH_FOUND이고_STOCK_LEAK을_남긴다() {
        CouponPolicy policy = policy(1L, 10);
        VerificationReport report = pendingReport(policy);
        List<Long> userIds = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(userIds);
        when(setOperations.members(any())).thenReturn(Set.of("1", "2", "3", "4", "5", "6")); // diff 없음
        when(zSetOperations.size(any())).thenReturn(1L); // RESERVED 1건
        // stock 키가 실제로 존재하고 값이 0(진짜 소진) -> totalReservedEver=10, dbIssued(6)+reserved(1)=7 -> leak=3
        when(valueOperations.get(any())).thenReturn("0");
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports", "leak.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(3);
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(1L, 2L, 3L, 4L, 5L, 6L), Set.of(1L, 2L, 3L, 4L, 5L, 6L), 0, 3,
                        List.of(), Set.of())));
    }

    @Test
    void 재고_카운터_키가_없으면_Check_A를_건너뛰고_오탐하지_않는다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.members(any())).thenReturn(Set.of("100", "200", "300")); // diff 없음
        when(zSetOperations.size(any())).thenReturn(0L);
        // stock 스텁 안 함 -> 키 없음 -> Check A 건너뜀

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(report.getMismatchCount()).isZero();
        verify(mismatchReportWriter, never()).write(any(), any(), any());
    }

    @Test
    void 이력_최신상태가_현재상태와_다르면_HISTORY_MISMATCH이다() {
        CouponPolicy policy = policy(1L, 1);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.members(any())).thenReturn(Set.of("100"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(1L)).thenReturn(
                List.of(new CouponIssueLifecycleSnapshot(10L, 100L, IssueStatus.USED, LocalDateTime.now())));
        when(couponHistoryRepository.findStatusSnapshotsByCouponPolicyId(1L)).thenReturn(
                List.of(new CouponHistoryStatusSnapshot(10L, IssueStatus.ISSUED))); // 최신 이력은 ISSUED인데 현재는 USED
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports", "history.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(1);
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(100L), Set.of(100L), 0, 0,
                        List.of(new LifecycleAnomaly(10L, 100L, "HISTORY_MISMATCH")), Set.of())));
    }

    @Test
    void 이력이_없는데_usedAt이_채워져있으면_MISSING_HISTORY이다() {
        CouponPolicy policy = policy(1L, 1);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.members(any())).thenReturn(Set.of("100"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(1L)).thenReturn(
                List.of(new CouponIssueLifecycleSnapshot(10L, 100L, IssueStatus.USED, LocalDateTime.now())));
        // 이력 0건(setUp의 기본 스텁 그대로)
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports", "missing1.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getMismatchCount()).isEqualTo(1);
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(100L), Set.of(100L), 0, 0,
                        List.of(new LifecycleAnomaly(10L, 100L, "MISSING_HISTORY")), Set.of())));
    }

    @Test
    void 이력이_없어도_status가_EXPIRED이면_usedAt이_null이어도_MISSING_HISTORY이다() {
        CouponPolicy policy = policy(1L, 1);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.members(any())).thenReturn(Set.of("100"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(1L)).thenReturn(
                List.of(new CouponIssueLifecycleSnapshot(10L, 100L, IssueStatus.EXPIRED, null)));
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports", "missing2.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getMismatchCount()).isEqualTo(1);
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(100L), Set.of(100L), 0, 0,
                        List.of(new LifecycleAnomaly(10L, 100L, "MISSING_HISTORY")), Set.of())));
    }

    @Test
    void 이력이_없어도_신규발급_상태면_오탐하지_않는다() {
        CouponPolicy policy = policy(1L, 1);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.members(any())).thenReturn(Set.of("100"));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(1L)).thenReturn(
                List.of(new CouponIssueLifecycleSnapshot(10L, 100L, IssueStatus.ISSUED, null)));

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(report.getMismatchCount()).isZero();
        verify(mismatchReportWriter, never()).write(any(), any(), any());
    }

    @Test
    void 리포트가_없으면_예외가_발생하고_execute는_이를_잡아서_로그만_남긴다() {
        when(verificationReportRepository.findById(999L)).thenReturn(Optional.empty());

        // execute()는 예외를 던지지 않고 내부에서 잡아 로그만 남긴다 (백그라운드 스레드라 던져봐야 받을 곳이 없음)
        asyncTrigger.execute(999L);
    }

    @Test
    void 대사_도중_예외가_나면_리포트를_FAILED로_확정하고_사유를_남긴다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        // findById가 execute() 본문에서 한 번, 실패 후 markFailed()에서 한 번 — 총 두 번 불린다
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L))
                .thenThrow(new IllegalStateException("Redis 연결 실패 시뮬레이션"));

        asyncTrigger.execute(1L); // 예외를 던지지 않고 내부에서 흡수해야 한다

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(report.getFailureReason()).isEqualTo("Redis 연결 실패 시뮬레이션");
    }

    @Test
    void CSV_쓰기가_실패하면_MISMATCH_FOUND로_먼저_확정되지_않고_FAILED로_처리된다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.members(any())).thenReturn(Set.of()); // Redis엔 없음 -> diff 1건 -> CSV 쓰기 시도
        when(zSetOperations.size(any())).thenReturn(0L);
        when(mismatchReportWriter.write(any(), any(), any()))
                .thenThrow(new java.io.UncheckedIOException(
                        "검증 불일치 리포트(CSV) 작성 실패", new java.io.IOException("디스크 쓰기 실패 시뮬레이션")));

        asyncTrigger.execute(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(report.getFailureReason()).contains("검증 불일치 리포트(CSV) 작성 실패");
        assertThat(report.getMismatchCount()).isZero(); // complete()가 호출된 적이 없어야 함
        assertThat(report.getReportUrl()).isNull();
    }

    @Test
    void 이미_최종_상태인_리포트는_실패_처리로_덮어쓰지_않는다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        report.complete(3, 0, 0, VerificationStatus.SUCCESS); // 이미 끝난 상태를 시뮬레이션
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L))
                .thenThrow(new IllegalStateException("이 시점엔 이미 SUCCESS라 가정상 도달 안 함"));

        asyncTrigger.execute(1L);

        // fail()이 호출됐다면 IllegalStateException이 나서 markFailed 내부에서 로그만 남고 상태는 그대로여야 한다
        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
    }
}
