package com.ureca.myureca.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponHistoryStatusSnapshot;
import com.ureca.myureca.repository.CouponIssueLifecycleSnapshot;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.QueueJoinLogRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import com.ureca.myureca.support.RedisKeys;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PENDING으로 접수된 검증 리포트를 백그라운드에서 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationAsyncTrigger {

    private final CouponIssueRepository couponIssueRepository;
    private final VerificationReportRepository verificationReportRepository;
    private final StringRedisTemplate redisTemplate;
    private final MismatchReportWriter mismatchReportWriter;
    private final CouponHistoryRepository couponHistoryRepository;
    private final QueueJoinLogRepository queueJoinLogRepository;

    /**
     * 자기 자신의 Spring 프록시. {@link #execute}는 트랜잭션 <b>밖</b>에 있어야 하고
     * ({@link #execute} 주석 참고) {@link #performVerification}·{@link #markFailed}는 각각 별개
     * 트랜잭션이어야 하는데, 같은 클래스 안에서 그냥 호출하면 self-invoke라 AOP 프록시를 안 거쳐
     * {@code @Transactional}이 통째로 무시된다. {@code ObjectProvider}로 지연 조회하면 자기 참조
     * 순환 의존성 없이 프록시를 얻을 수 있다.
     */
    private final ObjectProvider<VerificationAsyncTrigger> selfProvider;

    /**
     * 검증 실행 오케스트레이션 전담. <b>이 메서드에는 {@code @Transactional}을 붙이면 안 된다.</b>
     *
     * <p>예전에는 여기에 {@code @Transactional}이 붙어 있고 {@code catch} 안에서 리포트를 FAILED로
     * 바꿨는데, 실패 원인이 DB 예외인 경우 Hibernate가 이미 그 트랜잭션을 rollback-only로 마킹한
     * 뒤라 {@code report.fail()}의 더티체킹이 절대 커밋되지 않았다(이슈 #11/#17에서 Kafka 컨슈머에
     * 대해 잡았던 것과 정확히 같은 패턴). 그 결과가 단순 로그 누락이 아니라는 게 문제였다 —
     * {@code VerificationService.dispatch()}는 PENDING 리포트가 있으면 새 검증을 접수하지 않으므로,
     * 한 번 PENDING에 갇히면 <b>그 정책은 다시는 검증을 돌릴 수 없었다.</b> 게다가 이 메서드는
     * {@code @Async}라 예외가 아무 데도 안 올라가서 조용히 그렇게 됐다.
     *
     * <p>그래서 트랜잭션 경계를 둘로 쪼갰다: 실제 대사 작업({@link #performVerification})이 자기
     * 트랜잭션에서 롤백되더라도, 실패 기록({@link #markFailed})은 그와 무관한 새 트랜잭션에서
     * 독립적으로 커밋된다.
     */
    @Async("verificationTaskExecutor")
    public void execute(Long reportId) {
        VerificationAsyncTrigger self = selfProvider.getObject();
        try {
            self.performVerification(reportId);
        } catch (Exception e) {
            // 백그라운드 스레드라 예외를 던져봐야 받아줄 호출자가 없다 — 여기서 잡아 로그로 남긴다.
            log.error("검증 배치 비동기 실행 실패. reportId={}", reportId, e);
            self.markFailed(reportId, e);
        }
    }

    /**
     * 실패한 리포트를 FAILED로 확정한다.
     *
     * <p>{@code REQUIRES_NEW}인 이유: 호출 시점에 이미 {@link #performVerification}의 트랜잭션이
     * 롤백된 뒤이고, 혹시라도 바깥에 rollback-only로 마킹된 트랜잭션이 살아있다면 그 안에서
     * 쓰기를 시도해봐야 커밋되지 않는다. 실패 기록은 검증 작업의 성패와 완전히 독립적으로
     * 남아야 하므로 항상 새 트랜잭션에서 수행한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long reportId, Exception cause) {
        try {
            verificationReportRepository.findById(reportId).ifPresent(report -> {
                if (report.getStatus() == VerificationStatus.PENDING) {
                    report.fail(summarize(cause));
                }
            });
        } catch (Exception markFailure) {
            log.error("검증 실패 상태 기록 자체가 실패함. reportId={}", reportId, markFailure);
        }
    }

    private String summarize(Exception e) {
        String message = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * 실제 대사(비교) 작업. {@code public}인 이유는 가시성이 필요해서가 아니라, Spring의
     * {@code AnnotationTransactionAttributeSource}가 <b>public 메서드에만</b>
     * {@code @Transactional}을 적용하기 때문이다 — package-private으로 두면 프록시를 거쳐도
     * 트랜잭션이 조용히 무시되고, 이 메서드가 의존하는 지연 로딩·더티체킹이 전부 깨진다.
     */
    @Transactional
    public void performVerification(Long reportId) {
        VerificationReport report = verificationReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalStateException("검증 리포트를 찾을 수 없습니다. id=" + reportId));

        CouponPolicy policy = report.getCouponPolicy();
        Long policyId = policy.getId();
        LocalDateTime runAt = report.getRunAt();

        Set<Long> dbUserIds = new HashSet<>(couponIssueRepository.findUserIdsByCouponPolicyId(policyId));
        Set<Long> redisUserIds = readRedisIssuedUserIds(policyId);
        long totalReserved = readRedisReservedCount(policyId);

        int overIssuedCount = Math.max(0, dbUserIds.size() - policy.getTotalQuantity());
        if (overIssuedCount > 0) {
            log.error("🚨 정책 id={} 초과발급 의심: 발급 {}건 > 재고 {}건 (NFR-1 위반)",
                    policyId, dbUserIds.size(), policy.getTotalQuantity());
        }

        int diffMismatchCount = countMismatch(dbUserIds, redisUserIds);

        Integer currentRedisStock = readRedisStockCounter(policyId);
        int stockLeakCount;
        if (currentRedisStock == null) {
            log.warn("정책 id={} 재고 카운터({})가 초기화된 적이 없어 재고 누수 체크를 건너뜁니다.",
                    policyId, RedisKeys.couponStock(policyId));
            stockLeakCount = 0;
        } else {
            stockLeakCount = computeStockLeakCount(
                    policyId, policy.getTotalQuantity(), currentRedisStock, dbUserIds.size(), totalReserved);
        }

        List<CouponIssueLifecycleSnapshot> lifecycleSnapshots = fetchLifecycleSnapshots(policyId);
        Map<Long, IssueStatus> latestHistoryStatusByIssueId = fetchLatestHistoryStatusByIssueId(policyId);
        List<LifecycleAnomaly> lifecycleAnomalies =
                detectLifecycleAnomalies(policyId, lifecycleSnapshots, latestHistoryStatusByIssueId);

        // Check C: 선착순(FCFS) 순서 검증 — "대기열 도착 순서 상위 N명"과 "실제 DB 발급자 집합"을 비교
        int liveN = Math.min(dbUserIds.size(), policy.getTotalQuantity());
        long coveredCount = queueJoinLogRepository.countByCouponPolicyIdAndQueueRankLessThanEqual(
                policyId, (long) liveN);
        Set<Long> expectedTopN;
        int fcfsMismatchCount;
        if (coveredCount < liveN) {
            log.warn("정책 id={} queue_join_log의 순번 구간 [1,{}] 적재분({}건)이 아직 채워지지 않아 "
                    + "선착순(FCFS) 검증을 건너뜁니다 (queue-join-events 컨슈머 미가동 또는 캐치업 중으로 추정).",
                    policyId, liveN, coveredCount);
            expectedTopN = Set.of();
            fcfsMismatchCount = 0;
        } else {
            expectedTopN = fetchExpectedTopN(policyId, liveN);
            fcfsMismatchCount = countMismatch(dbUserIds, expectedTopN);
        }

        int mismatchCount =
                diffMismatchCount + overIssuedCount + stockLeakCount + lifecycleAnomalies.size() + fcfsMismatchCount;
        VerificationStatus status = (mismatchCount == 0) ? VerificationStatus.SUCCESS : VerificationStatus.MISMATCH_FOUND;

        if (totalReserved > 0) {
            log.warn("정책 id={} 검증 시점에 RESERVED가 {}건 남아있음 — 마감 전이거나 미아 예약 의심",
                    policyId, totalReserved);
        }
        if (fcfsMismatchCount > 0) {
            log.error("🚨 정책 id={} 선착순(FCFS) 위반 의심: {}쌍 (도착순 상위 N명 ↔ 실제 발급자 불일치)",
                    policyId, fcfsMismatchCount);
        }

        Path csvPath = null;
        if (diffMismatchCount > 0 || overIssuedCount > 0 || stockLeakCount > 0
                || !lifecycleAnomalies.isEmpty() || fcfsMismatchCount > 0) {
            MismatchFindings findings = new MismatchFindings(
                    dbUserIds, redisUserIds, overIssuedCount, stockLeakCount, lifecycleAnomalies, expectedTopN);
            csvPath = mismatchReportWriter.write(policyId, runAt, findings);
        }

        report.complete(dbUserIds.size(), (int) totalReserved, mismatchCount, status);
        if (csvPath != null) {
            report.attachReportUrl(csvPath.toString());
        }
    }

    private Set<Long> readRedisIssuedUserIds(Long policyId) {
        Set<String> raw = redisTemplate.opsForSet().members(RedisKeys.couponIssued(policyId));
        if (raw == null) {
            return Set.of();
        }
        Set<Long> result = new HashSet<>(raw.size());
        for (String value : raw) {
            result.add(Long.valueOf(value));
        }
        return result;
    }

    private long readRedisReservedCount(Long policyId) {
        Long size = redisTemplate.opsForZSet().size(RedisKeys.couponReserved(policyId));
        return (size == null) ? 0L : size;
    }

    /** 대칭차집합 크기 = REDIS_ONLY 건수 + DB_ONLY 건수. */
    private int countMismatch(Set<Long> dbUserIds, Set<Long> redisUserIds) {
        Set<Long> redisOnly = new HashSet<>(redisUserIds);
        redisOnly.removeAll(dbUserIds);
        Set<Long> dbOnly = new HashSet<>(dbUserIds);
        dbOnly.removeAll(redisUserIds);
        return redisOnly.size() + dbOnly.size();
    }

    /**
     * Redis 실시간 재고 카운터
     */
    private Integer readRedisStockCounter(Long policyId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.couponStock(policyId));
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Check A: 재고 누수
     */
    private int computeStockLeakCount(
            Long policyId, int totalQuantity, int currentRedisStock, int dbIssuedCount, long totalReserved) {
        int totalReservedEver = totalQuantity - currentRedisStock;
        long confirmedOrPending = dbIssuedCount + totalReserved;
        long leak = totalReservedEver - confirmedOrPending;

        if (leak < 0) {
            log.warn("정책 id={} 재고 누수 계산에서 음수 발생: totalReservedEver={}, dbIssued={}, reserved={}",
                    policyId, totalReservedEver, dbIssuedCount, totalReserved);
            return 0;
        }
        if (leak > 0) {
            log.error("정책 id={} 재고 누수 의심: totalQuantity={}, currentRedisStock={}, dbIssued={}, "
                            + "reserved={}, leak={}건",
                    policyId, totalQuantity, currentRedisStock, dbIssuedCount, totalReserved, leak);
        }
        return (int) leak;
    }

    private List<CouponIssueLifecycleSnapshot> fetchLifecycleSnapshots(Long policyId) {
        return couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(policyId);
    }

    /** couponIssueId asc, id asc 순으로 오는 결과를 순회하며 마지막 값으로 덮어써 최신 이력만 남긴다. */
    private Map<Long, IssueStatus> fetchLatestHistoryStatusByIssueId(Long policyId) {
        List<CouponHistoryStatusSnapshot> snapshots =
                couponHistoryRepository.findStatusSnapshotsByCouponPolicyId(policyId);
        Map<Long, IssueStatus> latest = new HashMap<>();
        for (CouponHistoryStatusSnapshot snapshot : snapshots) {
            latest.put(snapshot.issueId(), snapshot.newStatus());
        }
        return latest;
    }

    /**
     * Check C: 선착순(FCFS) 검증용 — 대기열 순번(queue_rank=seq) 상위 N명. 호출부가 liveN을
     * totalQuantity로 이미 클램프하고 적재량도 확인한 뒤에만 부른다(performVerification() 참고).
     */
    private Set<Long> fetchExpectedTopN(Long policyId, int liveN) {
        if (liveN <= 0) {
            return Set.of();
        }
        return new HashSet<>(queueJoinLogRepository.findUserIdsOrderByQueueRankAsc(
                policyId, PageRequest.of(0, liveN)));
    }

    /**
     * Check B: 생명주기 불일치.
     */
    private List<LifecycleAnomaly> detectLifecycleAnomalies(
            Long policyId,
            List<CouponIssueLifecycleSnapshot> snapshots,
            Map<Long, IssueStatus> latestHistoryStatusByIssueId
    ) {
        List<LifecycleAnomaly> anomalies = new ArrayList<>();
        for (CouponIssueLifecycleSnapshot snapshot : snapshots) {
            IssueStatus latestHistoryStatus = latestHistoryStatusByIssueId.get(snapshot.issueId());
            if (latestHistoryStatus != null) {
                if (latestHistoryStatus != snapshot.status()) {
                    anomalies.add(new LifecycleAnomaly(snapshot.issueId(), snapshot.userId(), "HISTORY_MISMATCH"));
                }
                continue;
            }
            boolean hasTransitionEvidence = snapshot.status() != IssueStatus.ISSUED || snapshot.usedAt() != null;
            if (hasTransitionEvidence) {
                anomalies.add(new LifecycleAnomaly(snapshot.issueId(), snapshot.userId(), "MISSING_HISTORY"));
            }
        }
        if (!anomalies.isEmpty()) {
            log.error("정책 id={} 생명주기 불일치 {}건 발견(coupon_history vs coupon_issue.status)",
                    policyId, anomalies.size());
        }
        return anomalies;
    }

    /** 생명주기 불일치 1건. type은 "HISTORY_MISMATCH" 또는 "MISSING_HISTORY". */
    public record LifecycleAnomaly(Long issueId, Long userId, String type) {
    }

    /** performVerification()에서 계산한 불일치 결과를 CSV 작성기로 넘기기 위한 묶음. */
    public record MismatchFindings(
            Set<Long> dbUserIds,
            Set<Long> redisUserIds,
            int overIssuedCount,
            int stockLeakCount,
            List<LifecycleAnomaly> lifecycleAnomalies,
            /** Check C(FCFS): 대기열 도착 순서 상위 N명(이론상 당첨자). dbUserIds와 비교해 CSV에 반영한다. */
            Set<Long> expectedTopN
    ) {
    }

    /** CSV 리포트 작성 전담. 파일 I/O를 위 로직에서 분리해 테스트하기 쉽게 한다. */
    @Component
    public static class MismatchReportWriter {

        private static final String HEADER = "policyId,userId,couponIssueId,discrepancyType,detectedAt\n";

        private final Path reportDir;

        public MismatchReportWriter(
                @Value("${app.verification.report-dir:reports}") String reportDir
        ) {
            this.reportDir = Path.of(reportDir);
        }

        public Path write(Long policyId, LocalDateTime runAt, MismatchFindings findings) {
            Set<Long> redisOnly = new HashSet<>(findings.redisUserIds());
            redisOnly.removeAll(findings.dbUserIds());
            Set<Long> dbOnly = new HashSet<>(findings.dbUserIds());
            dbOnly.removeAll(findings.redisUserIds());

            StringBuilder csv = new StringBuilder(HEADER);
            appendUserRows(csv, policyId, redisOnly, "REDIS_ONLY", runAt);
            appendUserRows(csv, policyId, dbOnly, "DB_ONLY", runAt);
            if (findings.overIssuedCount() > 0) {
                // 특정 user_id/coupon_issue_id에 귀속되는 문제가 아니라 정책 전체의 집계 사실이라 비운다.
                appendPolicyLevelRow(csv, policyId, "OVERSOLD(+" + findings.overIssuedCount() + ')', runAt);
            }
            if (findings.stockLeakCount() > 0) {
                appendPolicyLevelRow(csv, policyId, "STOCK_LEAK(+" + findings.stockLeakCount() + ')', runAt);
            }
            appendLifecycleRows(csv, policyId, findings.lifecycleAnomalies(), runAt);

            // Check C(FCFS): 도착순 상위 N명(expectedTopN) vs 실제 DB 발급자(dbUserIds) 경계 비교.
            Set<Long> expectedNotIssued = new HashSet<>(findings.expectedTopN());
            expectedNotIssued.removeAll(findings.dbUserIds());
            Set<Long> issuedNotExpected = new HashSet<>(findings.dbUserIds());
            issuedNotExpected.removeAll(findings.expectedTopN());
            appendUserRows(csv, policyId, expectedNotIssued, "EXPECTED_NOT_ISSUED", runAt);
            appendUserRows(csv, policyId, issuedNotExpected, "ISSUED_NOT_EXPECTED", runAt);

            try {
                Files.createDirectories(reportDir);
                Path file = reportDir.resolve("verification-%d-%d.csv".formatted(policyId, runAt.toInstant(
                        java.time.ZoneOffset.ofHours(9)).toEpochMilli()));
                Files.writeString(file, csv.toString());
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException("검증 불일치 리포트(CSV) 작성 실패. policyId=" + policyId, e);
            }
        }

        private void appendUserRows(StringBuilder csv, Long policyId, Set<Long> userIds, String type, LocalDateTime runAt) {
            for (Long userId : userIds) {
                csv.append(policyId).append(',')
                        .append(userId).append(",,") // couponIssueId 칸은 비움
                        .append(type).append(',')
                        .append(runAt)
                        .append('\n');
            }
        }

        private void appendLifecycleRows(
                StringBuilder csv, Long policyId, List<LifecycleAnomaly> anomalies, LocalDateTime runAt) {
            for (LifecycleAnomaly anomaly : anomalies) {
                csv.append(policyId).append(',')
                        .append(anomaly.userId()).append(',')
                        .append(anomaly.issueId()).append(',')
                        .append(anomaly.type()).append(',')
                        .append(runAt)
                        .append('\n');
            }
        }

        /** userId/couponIssueId 둘 다 특정 대상이 없는 정책 단위 요약 행(OVERSOLD, STOCK_LEAK). */
        private void appendPolicyLevelRow(StringBuilder csv, Long policyId, String discrepancyType, LocalDateTime runAt) {
            csv.append(policyId).append(",,,")
                    .append(discrepancyType).append(',')
                    .append(runAt)
                    .append('\n');
        }

        /**
         * 다운로드용 경로 해석.
         */
        public Path resolveExistingFile(String storedReportUrl) {
            Path resolved;
            try {
                resolved = Path.of(storedReportUrl).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                // Path.of() 자체가 IllegalStateException이 아니라 InvalidPathException(IllegalArgumentException의
                // 하위 타입)을 던진다 — DB 값 오염을 방어하려는 이 메서드의 목적을 그대로 지키려면
                // 호출부가 잡는 예외 타입(IllegalStateException) 하나로 통일해서 다시 던져야 한다.
                throw new IllegalStateException("reportUrl이 올바른 경로 형식이 아닙니다: " + storedReportUrl, e);
            }
            Path root = reportDir.toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalStateException(
                        "reportUrl이 reportDir(" + root + ") 밖을 가리킵니다: " + storedReportUrl);
            }
            return resolved;
        }
    }
}
