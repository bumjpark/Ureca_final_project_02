package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.queue.QueueJoinLog;
import com.ureca.myureca.domain.queue.QueueStatus;
import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.dto.response.VerificationReportResponse;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.QueueJoinLogRepository;
import com.ureca.myureca.repository.UserRepository;
import com.ureca.myureca.support.RedisKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * queue-join-events/coupon_issue 컨슈머가 아직 없으므로(카프카 개발 중),
 * queue_join_log/coupon_issue를 직접 seed해서 검증 로직 자체만 먼저 DB+Redis로 검증한다.
 * 컨슈머가 완성되면 이 테스트의 seed 부분만 실제 이벤트 발행/소비로 대체하면 되고,
 * 검증 로직(Check C) 자체는 이 테스트가 커버한다.
 */
@SpringBootTest
class FcfsVerificationIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CouponPolicyRepository couponPolicyRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private QueueJoinLogRepository queueJoinLogRepository;

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void 도착순위_밖에서_발급되면_실제_DB_Redis로_검증해도_FCFS_위반이_잡힌다() throws Exception {
        // Given: 재고 3장짜리 정책
        CouponPolicy policy = couponPolicyRepository.save(new CouponPolicy(
                "FCFS 통합테스트 정책", CouponType.FIXED, 1000, 3,
                LocalDateTime.now().minusHours(1), null));
        Long policyId = policy.getId();

        redisTemplate.delete(RedisKeys.couponIssued(policyId));
        redisTemplate.delete(RedisKeys.couponReserved(policyId));
        redisTemplate.delete(RedisKeys.couponStock(policyId));

        User u1 = user();
        User u2 = user();
        User u3 = user();
        User u4 = user();

        // 대기열 도착 순서(queue_rank=seq, 절대 불변): u1 < u2 < u3 < u4
        LocalDateTime joinedAt = LocalDateTime.now().minusMinutes(10);
        queueJoinLogRepository.save(new QueueJoinLog(policyId, u1.getId(), QueueStatus.ADMITTED, 1L, joinedAt));
        queueJoinLogRepository.save(new QueueJoinLog(policyId, u2.getId(), QueueStatus.ADMITTED, 2L, joinedAt));
        queueJoinLogRepository.save(new QueueJoinLog(policyId, u3.getId(), QueueStatus.ADMITTED, 3L, joinedAt));
        queueJoinLogRepository.save(new QueueJoinLog(policyId, u4.getId(), QueueStatus.WAITING, 4L, joinedAt));

        // 재고 3장인데 실제 발급은 u1, u2, u4 — 도착순 3등(u3)이 밀리고 4등(u4)이 새치기한 상황을 시뮬레이션
        LocalDateTime issuedAt = LocalDateTime.now();
        couponIssueRepository.save(new CouponIssue(policy, u1, "rcpt-" + u1.getId(), issuedAt));
        couponIssueRepository.save(new CouponIssue(policy, u2, "rcpt-" + u2.getId(), issuedAt));
        couponIssueRepository.save(new CouponIssue(policy, u4, "rcpt-" + u4.getId(), issuedAt));

        // Redis issued SET을 DB 발급자와 동일하게 맞춰서 Check A(diff)는 0건으로 고정 — FCFS(Check C)만 순수하게 검증
        redisTemplate.opsForSet().add(RedisKeys.couponIssued(policyId),
                String.valueOf(u1.getId()), String.valueOf(u2.getId()), String.valueOf(u4.getId()));

        // When
        List<VerificationReportResponse> dispatched = verificationService.runVerification(policyId, true);
        assertThat(dispatched).hasSize(1);
        Long reportId = dispatched.get(0).id();

        VerificationReportResponse finalReport = awaitCompletion(reportId);

        // Then
        assertThat(finalReport.status()).isEqualTo(VerificationStatus.MISMATCH_FOUND);
        assertThat(finalReport.mismatchCount()).isEqualTo(2); // (u3 EXPECTED_NOT_ISSUED, u4 ISSUED_NOT_EXPECTED) 1쌍=2건
        assertThat(finalReport.reportUrl()).isNotNull();

        String csv = Files.readString(Path.of(finalReport.reportUrl()));
        assertThat(csv).contains(policyId + "," + u3.getId() + ",,EXPECTED_NOT_ISSUED");
        assertThat(csv).contains(policyId + "," + u4.getId() + ",,ISSUED_NOT_EXPECTED");
        // 도착순대로 정상 발급된 u1, u2는 FCFS 불일치 어느 쪽에도 없어야 한다
        assertThat(csv).doesNotContain(u1.getId() + ",,EXPECTED_NOT_ISSUED");
        assertThat(csv).doesNotContain(u1.getId() + ",,ISSUED_NOT_EXPECTED");
        assertThat(csv).doesNotContain(u2.getId() + ",,EXPECTED_NOT_ISSUED");
        assertThat(csv).doesNotContain(u2.getId() + ",,ISSUED_NOT_EXPECTED");
    }

    private User user() {
        return userRepository.save(new User(UUID.randomUUID() + "@fcfs-it.test", "테스트유저"));
    }

    /** performVerification()은 @Async라 별도 스레드에서 돈다 — PENDING을 벗어날 때까지 폴링한다. */
    private VerificationReportResponse awaitCompletion(Long reportId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            VerificationReportResponse report = verificationService.getVerificationReport(reportId);
            if (report.status() != VerificationStatus.PENDING) {
                return report;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("검증 리포트가 제한 시간(10초) 안에 PENDING을 벗어나지 못했습니다. reportId=" + reportId);
    }
}
