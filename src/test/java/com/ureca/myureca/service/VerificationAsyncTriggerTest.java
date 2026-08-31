package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.ureca.myureca.repository.ReconciliationLogRepository;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

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

    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

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
        // REDIS_ONLY 드리프트 등록(신규) — 대부분의 기존 테스트는 이 경로와 무관하므로
        // "아직 등록된 적 없음"(이미 등록된 eventKey 없음) 기본값으로 흐른다.
        org.mockito.Mockito.lenient()
                .when(reconciliationLogRepository.findExistingEventKeys(any())).thenReturn(Set.of());

        // execute()가 트랜잭션 프록시를 얻어오는 자기 참조. 유닛 테스트에는 프록시가 없으므로
        // 자기 자신을 그대로 돌려준다(이 테스트들은 performVerification을 직접 호출하지만,
        // execute() 경로를 검증하는 테스트를 위해 함께 배선해 둔다).
        @SuppressWarnings("unchecked")
        ObjectProvider<VerificationAsyncTrigger> selfProvider = mock(ObjectProvider.class);

        // Check D(미아 예약): 기본은 "오래된 예약 없음". 이 경로를 검증하는 테스트만 개별로 덮어쓴다.
        org.mockito.Mockito.lenient()
                .when(zSetOperations.rangeByScore(any(), org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Set.of());

        asyncTrigger = new VerificationAsyncTrigger(
                couponIssueRepository, verificationReportRepository, redisTemplate, mismatchReportWriter,
                couponHistoryRepository, queueJoinLogRepository, reconciliationLogRepository, new ObjectMapper(),
                selfProvider, java.time.Duration.ofMinutes(5)
        );
        org.mockito.Mockito.lenient().when(selfProvider.getObject()).thenReturn(asyncTrigger);
    }

    /**
     * {@code readRedisIssuedUserIds}가 SMEMBERS 대신 SSCAN 커서로 바뀌면서(2026-08-30, 300만 건
     * 규모 실측에서 SMEMBERS 타임아웃을 발견해 수정), 테스트도 opsForSet().members() 대신
     * opsForSet().scan()이 돌려주는 Cursor&lt;String&gt;을 흉내 내야 한다. 실제 구현은 hasNext()/
     * next()/close()만 쓰므로 그 셋만 값 있게 구현하고 나머지는 이 테스트에서 호출될 일이 없다.
     */
    private Cursor<String> cursorOf(Set<String> values) {
        java.util.Iterator<String> it = values.iterator();
        return new Cursor<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public String next() {
                return it.next();
            }

            @Override
            public void close() {
                // no-op
            }

            @Override
            public CursorId getId() {
                return CursorId.initial();
            }

            @Override
            public long getCursorId() {
                return 0L;
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public long getPosition() {
                return 0L;
            }
        };
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

    /**
     * 대사 작업이 실패하면 리포트가 반드시 FAILED로 확정돼야 한다. PENDING으로 남으면
     * {@code VerificationService.dispatch()}가 그 정책의 재검증을 영구히 봉쇄한다.
     */
    @Test
    void 대사_작업이_실패하면_리포트를_FAILED로_확정한다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L))
                .thenThrow(new RuntimeException("DB 커넥션 끊김"));

        asyncTrigger.execute(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(report.getFailureReason()).contains("DB 커넥션 끊김");
    }

    @Test
    void 완전히_일치하면_SUCCESS로_완료된다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "200", "300")));
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
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "200", "300")));
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
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "200", "300")));
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
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "200", "999")));
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
                        Set.of(100L, 200L, 300L), Set.of(), true)));
    }

    @Test
    void Redis에만_있는_유저가_있으면_MISMATCH_FOUND이고_CSV를_생성한다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "200", "999")));
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
                eq(new MismatchFindings(Set.of(100L, 200L), Set.of(100L, 200L, 999L), 0, 0, List.of(), Set.of(), Set.of(), false)));
    }

    @Test
    void Redis에만_있는_유저가_있으면_reconciliation_log에_ISSUE_REPROCESS로_등록된다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "999")));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(valueOperations.get(any())).thenReturn("9999");
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports/x.csv"));

        asyncTrigger.performVerification(1L);

        org.mockito.ArgumentCaptor<List<com.ureca.myureca.domain.reconciliation.ReconciliationLog>> captor =
                org.mockito.ArgumentCaptor.captor();
        verify(reconciliationLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        com.ureca.myureca.domain.reconciliation.ReconciliationLog saved = captor.getValue().get(0);
        assertThat(saved.getType()).isEqualTo(com.ureca.myureca.domain.reconciliation.ReconciliationType.ISSUE_REPROCESS);
        assertThat(saved.getEventKey()).isEqualTo("verify-redis-only:1:999");
        assertThat(saved.getCouponIssue()).isNull();
        assertThat(saved.getFailReason()).contains("REDIS_ONLY");
    }

    @Test
    void 이미_등록된_REDIS_ONLY_드리프트는_중복_등록하지_않는다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "999")));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(valueOperations.get(any())).thenReturn("9999");
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports/x.csv"));
        when(reconciliationLogRepository.findExistingEventKeys(any()))
                .thenReturn(Set.of("verify-redis-only:1:999"));

        asyncTrigger.performVerification(1L);

        verify(reconciliationLogRepository, never()).saveAll(any());
    }

    /**
     * Lua가 재고를 깎고 reserved에 넣은 직후 Kafka 발행 전에 프로세스가 죽는 경로가 여기로 떨어진다.
     * 이 유저는 issued SET에 없어 REDIS_ONLY가 아니고, computeStockLeakCount도 reserved를
     * "처리 중"으로 세기 때문에 재고 누수로도 안 잡힌다 — Check D가 없으면 어디에도 안 걸린다.
     */
    @Test
    void 임계_시간을_넘긴_미아_예약은_MISMATCH이고_ISSUE_REPROCESS로_등록된다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100")));
        when(zSetOperations.size(any())).thenReturn(1L); // userId=777이 reserved에 남아있음
        when(valueOperations.get(any())).thenReturn("9998"); // 깎인 2건 = dbIssued(1) + reserved(1) → 누수 0
        when(zSetOperations.rangeByScore(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(Set.of("777"));
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports/x.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(1);

        org.mockito.ArgumentCaptor<List<com.ureca.myureca.domain.reconciliation.ReconciliationLog>> captor =
                org.mockito.ArgumentCaptor.captor();
        verify(reconciliationLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getEventKey()).isEqualTo("verify-reserved-stale:1:777");
        assertThat(captor.getValue().get(0).getFailReason()).contains("미아 예약");
    }

    @Test
    void 아직_임계_시간을_안_넘긴_예약은_정상_처리중으로_보고_오탐하지_않는다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100")));
        when(zSetOperations.size(any())).thenReturn(1L); // reserved에 1건 있지만
        when(valueOperations.get(any())).thenReturn("9998");
        // rangeByScore(임계 이전)는 비어있다 = 방금 예약된 정상 건 → setUp의 기본 스텁 그대로

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(report.getMismatchCount()).isZero();
        verify(reconciliationLogRepository, never()).saveAll(any());
    }

    @Test
    void 미아_예약이라도_이미_DB에_있으면_유실이_아니므로_오탐하지_않는다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100")));
        when(zSetOperations.size(any())).thenReturn(1L);
        when(valueOperations.get(any())).thenReturn("9998");
        // DB에 이미 있는 유저가 reserved에도 남아있는 상태 = 컨슈머가 ZREM만 못 한 것이라
        // 쿠폰은 정상 발급됐다. 재발급 대상이 아니다.
        when(zSetOperations.rangeByScore(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(Set.of("100"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getMismatchCount()).isZero();
        verify(reconciliationLogRepository, never()).saveAll(any());
    }

    @Test
    void DB에만_있는_유저가_있으면_MISMATCH_FOUND이다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "200")));
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
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100")));
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
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "200", "300"))); // Redis도 3명 — diff는 0
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
                eq(new MismatchFindings(Set.of(100L, 200L, 300L), Set.of(100L, 200L, 300L), 1, 0, List.of(), Set.of(), Set.of(), false)));
    }

    @Test
    void 재고_누수가_있으면_MISMATCH_FOUND이고_STOCK_LEAK을_남긴다() {
        CouponPolicy policy = policy(1L, 10);
        VerificationReport report = pendingReport(policy);
        List<Long> userIds = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(userIds);
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("1", "2", "3", "4", "5", "6"))); // diff 없음
        when(zSetOperations.size(any())).thenReturn(1L); // RESERVED 1건
        // stock 키가 실제로 존재하고 값이 0(진짜 소진) -> totalReservedEver=10, dbIssued(6)+reserved(1)=7 -> leak=3
        when(valueOperations.get(any())).thenReturn("0");
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports", "leak.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getStatus()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(report.getMismatchCount()).isEqualTo(3);
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(1L, 2L, 3L, 4L, 5L, 6L), Set.of(1L, 2L, 3L, 4L, 5L, 6L), 0, 3,
                        List.of(), Set.of(), Set.of(), false)));
    }

    @Test
    void 재고_카운터_키가_없으면_Check_A를_건너뛰고_오탐하지_않는다() {
        CouponPolicy policy = policy(1L, 10000);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L, 200L, 300L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100", "200", "300"))); // diff 없음
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
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100")));
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
                        List.of(new LifecycleAnomaly(10L, 100L, "HISTORY_MISMATCH")), Set.of(), Set.of(), false)));
    }

    @Test
    void 이력이_없는데_usedAt이_채워져있으면_MISSING_HISTORY이다() {
        CouponPolicy policy = policy(1L, 1);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100")));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(1L)).thenReturn(
                List.of(new CouponIssueLifecycleSnapshot(10L, 100L, IssueStatus.USED, LocalDateTime.now())));
        // 이력 0건(setUp의 기본 스텁 그대로)
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports", "missing1.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getMismatchCount()).isEqualTo(1);
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(100L), Set.of(100L), 0, 0,
                        List.of(new LifecycleAnomaly(10L, 100L, "MISSING_HISTORY")), Set.of(), Set.of(), false)));
    }

    @Test
    void 이력이_없어도_status가_EXPIRED이면_usedAt이_null이어도_MISSING_HISTORY이다() {
        CouponPolicy policy = policy(1L, 1);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100")));
        when(zSetOperations.size(any())).thenReturn(0L);
        when(couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(1L)).thenReturn(
                List.of(new CouponIssueLifecycleSnapshot(10L, 100L, IssueStatus.EXPIRED, null)));
        when(mismatchReportWriter.write(any(), any(), any())).thenReturn(Path.of("reports", "missing2.csv"));

        asyncTrigger.performVerification(1L);

        assertThat(report.getMismatchCount()).isEqualTo(1);
        verify(mismatchReportWriter).write(eq(1L), any(),
                eq(new MismatchFindings(Set.of(100L), Set.of(100L), 0, 0,
                        List.of(new LifecycleAnomaly(10L, 100L, "MISSING_HISTORY")), Set.of(), Set.of(), false)));
    }

    @Test
    void 이력이_없어도_신규발급_상태면_오탐하지_않는다() {
        CouponPolicy policy = policy(1L, 1);
        VerificationReport report = pendingReport(policy);
        when(verificationReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(100L));
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of("100")));
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
        when(setOperations.scan(any(), any())).thenReturn(cursorOf(Set.of())); // Redis엔 없음 -> diff 1건 -> CSV 쓰기 시도
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

    // ─── detectAndRegisterStaleReserved(Check D 독립 실행, RedisAutoRecoveryScheduler용) ──

    @Test
    void 미아_예약이_없으면_아무것도_등록하지_않는다() {
        when(zSetOperations.rangeByScore(eq("coupon:policy:1:reserved"),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Set.of());

        int found = asyncTrigger.detectAndRegisterStaleReserved(1L);

        assertThat(found).isZero();
        verify(couponIssueRepository, never()).findUserIdsByCouponPolicyId(any());
        verify(reconciliationLogRepository, never()).saveAll(any());
    }

    @Test
    void reserved에_있어도_DB에_이미_커밋된_건은_미아_예약이_아니다() {
        // ZREM만 못 한 것뿐(DB_ONLY 드리프트)이지 이벤트 유실이 아니다 — reconcileReservedDrift가
        // 따로 처리할 대상이라 여기서는 등록하면 안 된다.
        when(zSetOperations.rangeByScore(eq("coupon:policy:1:reserved"),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Set.of("10"));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(10L));

        int found = asyncTrigger.detectAndRegisterStaleReserved(1L);

        assertThat(found).isZero();
        verify(reconciliationLogRepository, never()).saveAll(any());
    }

    @Test
    void DB에도_없이_임계시간_넘긴_reserved는_미아_예약으로_등록한다() {
        when(zSetOperations.rangeByScore(eq("coupon:policy:1:reserved"),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Set.of("10", "20"));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of());

        int found = asyncTrigger.detectAndRegisterStaleReserved(1L);

        // 건별 save가 아니라 청크 단위 saveAll 한 번으로 묶여 나간다(2026-08-31 배치화).
        assertThat(found).isEqualTo(2);
        org.mockito.ArgumentCaptor<List<com.ureca.myureca.domain.reconciliation.ReconciliationLog>> captor =
                org.mockito.ArgumentCaptor.captor();
        verify(reconciliationLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(
                        com.ureca.myureca.domain.reconciliation.ReconciliationLog::getEventKey)
                .containsExactly("verify-reserved-stale:1:10", "verify-reserved-stale:1:20");
    }

    @Test
    void 이미_등록된_유저는_다시_등록하지_않고_비싼_DB조회도_건너뛴다() {
        // 이미 등록된 eventKey로 스텁 — 이전 틱에서 이미 등록됐다고 가정.
        when(zSetOperations.rangeByScore(eq("coupon:policy:1:reserved"),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Set.of("10"));
        when(reconciliationLogRepository.findExistingEventKeys(any()))
                .thenReturn(Set.of("verify-reserved-stale:1:10"));

        int found = asyncTrigger.detectAndRegisterStaleReserved(1L);

        // 미아 예약은 사람이 재처리하기 전까지 reserved에 계속 남아 매 틱 다시 발견된다.
        // 새로 할 일이 없으면 0을 돌려줘 스케줄러가 같은 경고를 60초마다 반복하지 않게 하고,
        // 정책 전체 user_id를 로드하는 비싼 조회까지 가기 전에 빠져나온다.
        assertThat(found).isZero();
        verify(couponIssueRepository, never()).findUserIdsByCouponPolicyId(any());
        verify(reconciliationLogRepository, never()).saveAll(any());
    }
}
