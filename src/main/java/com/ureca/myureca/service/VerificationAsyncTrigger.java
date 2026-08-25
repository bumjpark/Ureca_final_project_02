package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponHistoryStatusSnapshot;
import com.ureca.myureca.repository.CouponIssueLifecycleSnapshot;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import com.ureca.myureca.support.RedisKeys;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    @Async("verificationTaskExecutor")
    @Transactional
    public void execute(Long reportId) {
        try {
            performVerification(reportId);
        } catch (Exception e) {
            // 백그라운드 스레드라 예외를 던져봐야 받아줄 호출자가 없다 — 여기서 잡아 로그로 남긴다.
            log.error("검증 배치 비동기 실행 실패. reportId={}", reportId, e);
            markFailed(reportId, e);
        }
    }

    /**
     * 실패한 리포트를 FAILED로 확정
     */
    private void markFailed(Long reportId, Exception cause) {
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

    void performVerification(Long reportId) {
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

        int mismatchCount = diffMismatchCount + overIssuedCount + stockLeakCount + lifecycleAnomalies.size();
        VerificationStatus status = (mismatchCount == 0) ? VerificationStatus.SUCCESS : VerificationStatus.MISMATCH_FOUND;

        if (totalReserved > 0) {
            log.warn("정책 id={} 검증 시점에 RESERVED가 {}건 남아있음 — 마감 전이거나 미아 예약 의심",
                    policyId, totalReserved);
        }

        Path csvPath = null;
        if (diffMismatchCount > 0 || overIssuedCount > 0 || stockLeakCount > 0 || !lifecycleAnomalies.isEmpty()) {
            MismatchFindings findings = new MismatchFindings(
                    dbUserIds, redisUserIds, overIssuedCount, stockLeakCount, lifecycleAnomalies);
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
            List<LifecycleAnomaly> lifecycleAnomalies
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
         * 다운로드용 경로 해석. reportUrl은 클라이언트 입력이 아니라 서버가 직접 쓴 DB 저장값이라
         * 고전적 경로 탐색 공격 벡터는 아니지만, DB 값 오염에 대비한 defense-in-depth로 reportDir
         * 밖을 가리키면 방어적으로 거부한다. 항상 이 저장값에서 경로를 해석하고 policyId+runAt으로
         * 파일명을 재조합하지 않는다 — 동시 요청 시 같은 밀리초에 CSV 파일명이 충돌할 수 있는
         * 알려진 한계(Verification-Batch-Design.md)를 재계산으로 덮어쓰지 않기 위함이다.
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
