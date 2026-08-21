package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import com.ureca.myureca.support.RedisKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PENDING으로 접수된 검증 리포트를 백그라운드에서 실제로 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationAsyncTrigger {

    private final CouponIssueRepository couponIssueRepository;
    private final VerificationReportRepository verificationReportRepository;
    private final StringRedisTemplate redisTemplate;
    private final MismatchReportWriter mismatchReportWriter;

    @Async("verificationTaskExecutor")
    @Transactional
    public void execute(Long reportId) {
        try {
            performVerification(reportId);
        } catch (Exception e) {
            // 백그라운드 스레드라 예외를 던져봐야 받아줄 호출자가 없다 — 여기서 잡아 로그로 남긴다.
            log.error("검증 배치 비동기 실행 실패. reportId={}", reportId, e);
        }
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
        int mismatchCount = diffMismatchCount + overIssuedCount;
        VerificationStatus status = (mismatchCount == 0) ? VerificationStatus.SUCCESS : VerificationStatus.MISMATCH_FOUND;

        if (totalReserved > 0) {
            log.warn("정책 id={} 검증 시점에 RESERVED가 {}건 남아있음 — 마감 전이거나 미아 예약 의심",
                    policyId, totalReserved);
        }

        report.complete(dbUserIds.size(), (int) totalReserved, mismatchCount, status);

        if (diffMismatchCount > 0 || overIssuedCount > 0) {
            Path csvPath = mismatchReportWriter.write(policyId, runAt, dbUserIds, redisUserIds, overIssuedCount);
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

    /** CSV 리포트 작성 전담. 파일 I/O를 위 로직에서 분리해 테스트하기 쉽게 한다. */
    @Component
    public static class MismatchReportWriter {

        private static final String HEADER = "policyId,userId,discrepancyType,detectedAt\n";

        private final Path reportDir;

        public MismatchReportWriter(
                @Value("${app.verification.report-dir:reports}") String reportDir
        ) {
            this.reportDir = Path.of(reportDir);
        }

        public Path write(Long policyId, LocalDateTime runAt, Set<Long> dbUserIds, Set<Long> redisUserIds,
                int overIssuedCount) {
            Set<Long> redisOnly = new HashSet<>(redisUserIds);
            redisOnly.removeAll(dbUserIds);
            Set<Long> dbOnly = new HashSet<>(dbUserIds);
            dbOnly.removeAll(redisUserIds);

            StringBuilder csv = new StringBuilder(HEADER);
            appendRows(csv, policyId, redisOnly, "REDIS_ONLY", runAt);
            appendRows(csv, policyId, dbOnly, "DB_ONLY", runAt);
            if (overIssuedCount > 0) {
                // 특정 user_id에 귀속되는 문제가 아니라 정책 전체의 집계 사실이라 userId 칸은 비운다.
                csv.append(policyId).append(",,")
                        .append("OVERSOLD(+").append(overIssuedCount).append(')').append(',')
                        .append(runAt)
                        .append('\n');
            }

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

        private void appendRows(StringBuilder csv, Long policyId, Set<Long> userIds, String type, LocalDateTime runAt) {
            for (Long userId : userIds) {
                csv.append(policyId).append(',')
                        .append(userId).append(',')
                        .append(type).append(',')
                        .append(runAt)
                        .append('\n');
            }
        }
    }
}
