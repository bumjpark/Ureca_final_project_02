package com.ureca.myureca.service;

import com.ureca.myureca.domain.notification.MockNotificationBulkJob;
import com.ureca.myureca.domain.notification.MockNotificationLog;
import com.ureca.myureca.dto.request.KakaoNotificationRequest;
import com.ureca.myureca.dto.response.KakaoNotificationResponse;
import com.ureca.myureca.dto.response.MockNotificationBulkJobResponse;
import com.ureca.myureca.dto.response.MockNotificationLogResponse;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.MockNotificationBulkJobRepository;
import com.ureca.myureca.repository.MockNotificationLogRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Mock 카카오 알림톡(FR-5) 발송 전담. 이전에는 {@code MockNotificationController}가 발송
 * 로직(지연 시뮬레이션 + 성공/실패 판정)을 직접 들고 있었는데, 정책별 일괄 발송이 추가되면서
 * 컨트롤러가 "단건 발송"과 "여러 명에게 순차 발송" 두 흐름을 다 떠안는 게 부담스러워져
 * 서비스로 뽑았다. 발송할 때마다 결과를 {@code mock_notification_log}에 남긴다 —
 * 예전에는 발송 후 응답만 반환하고 어디에도 기록을 안 남겨서, 나중에 "뭘 보냈는지" 확인할
 * 방법이 프론트 세션 로컬 상태뿐이었다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockNotificationService {

    private final MockNotificationLogRepository mockNotificationLogRepository;
    private final MockNotificationBulkJobRepository mockNotificationBulkJobRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponPolicyRepository couponPolicyRepository;
    private final AsyncTaskExecutor mockNotificationAsyncTaskExecutor;

    @Value("${mock.kakao.min-latency-ms:50}")
    private long minLatencyMs;

    @Value("${mock.kakao.max-latency-ms:150}")
    private long maxLatencyMs;

    @Value("${mock.kakao.failure-rate:0}")
    private double failureRate;

    /** 단건 발송 — 지연 시뮬레이션 후 결과를 즉시 반환하고, 같은 결과를 로그에도 남긴다. */
    public KakaoNotificationResponse send(KakaoNotificationRequest request, boolean simulateFailure) {
        return sendAndLog(request.userId(), null, request.templateId(), request.message(), simulateFailure);
    }

    /**
     * 정책별 일괄 발송. 대상자(해당 정책으로 쿠폰을 발급받은 유저 전원)를
     * {@link CouponIssueRepository#findUserIdsByCouponPolicyId}로 조회한다 — 검증/재처리
     * 배치가 쓰는 것과 동일한 메서드를 그대로 재사용한다.
     *
     * <p>대상자가 많으면 순차 발송(건당 50~150ms 지연)이 수 초 이상 걸릴 수 있어, 호출 스레드를
     * 막지 않고 {@code mockNotificationAsyncTaskExecutor}에 전체 작업을 통째로 넘긴다 — 컨트롤러는
     * 대상자 수만 확인하고 즉시 202를 반환한다. "지금 이 정책 발송이 진행 중인지, 일부만 끝났는지"를
     * 보여주기 위해 시작 시점에 {@link MockNotificationBulkJob} 행을 만들고, 발송 루프가 건마다
     * 성공/실패 카운트를 그 행에 반영한다 — 루프 자체가 이 executor의 스레드 하나에서 순차 실행되므로
     * (한 작업 안에서 반복문을 도는 구조) 이 엔티티에 대한 동시 갱신 경합은 없다.
     *
     * @return 생성된 작업의 id와 대상자 수. 발송 자체는 백그라운드에서 계속된다.
     */
    public BulkSendResult sendBulkByPolicy(Long policyId, String templateId, String message) {
        couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        List<Long> userIds = couponIssueRepository.findUserIdsByCouponPolicyId(policyId);
        MockNotificationBulkJob job = mockNotificationBulkJobRepository.save(
                new MockNotificationBulkJob(policyId, templateId, message, userIds.size()));

        mockNotificationAsyncTaskExecutor.execute(() -> {
            for (Long userId : userIds) {
                try {
                    KakaoNotificationResponse res = sendAndLog(userId, policyId, templateId, message, false);
                    if ("SENT".equals(res.status())) {
                        job.recordSent();
                    } else {
                        job.recordFailed();
                    }
                } catch (Exception e) {
                    log.warn("[MockKakao] 일괄 발송 중 1건 실패 — policyId={}, userId={}", policyId, userId, e);
                    job.recordFailed();
                }
                mockNotificationBulkJobRepository.save(job);
            }
            job.complete();
            mockNotificationBulkJobRepository.save(job);
            log.info("[MockKakao] 일괄 발송 완료 — policyId={}, 대상 {}명, 성공 {}, 실패 {}",
                    policyId, userIds.size(), job.getSentCount(), job.getFailedCount());
        });
        return new BulkSendResult(job.getId(), userIds.size());
    }

    /** {@link #sendBulkByPolicy}의 반환값 — 작업 id와 확정된 대상자 수. */
    public record BulkSendResult(Long jobId, int targetCount) {
    }

    private KakaoNotificationResponse sendAndLog(
            Long userId, Long couponPolicyId, String templateId, String message, boolean simulateFailure
    ) {
        simulateNetworkLatency();

        String messageId = "mock-msg-" + UUID.randomUUID();
        boolean failed = simulateFailure || ThreadLocalRandom.current().nextDouble() < failureRate;

        if (failed) {
            log.info("[MockKakao] 발송 실패(mock) - userId={}, templateId={}, messageId={}", userId, templateId, messageId);
            mockNotificationLogRepository.save(
                    MockNotificationLog.failed(userId, couponPolicyId, templateId, message, messageId, "simulated failure"));
            return KakaoNotificationResponse.failed(messageId);
        }

        log.info("[MockKakao] 발송 성공(mock) - userId={}, templateId={}, messageId={}", userId, templateId, messageId);
        mockNotificationLogRepository.save(
                MockNotificationLog.sent(userId, couponPolicyId, templateId, message, messageId));
        return KakaoNotificationResponse.sent(messageId);
    }

    private void simulateNetworkLatency() {
        long delay = ThreadLocalRandom.current().nextLong(minLatencyMs, maxLatencyMs + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public PageResponse<MockNotificationLogResponse> getLogs(Long policyId, Pageable pageable) {
        var page = (policyId != null)
                ? mockNotificationLogRepository.findByCouponPolicyIdOrderByCreatedAtDesc(policyId, pageable)
                : mockNotificationLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.from(page.map(MockNotificationLogResponse::from));
    }

    /** 정책별 일괄 발송 진행 상태 조회 — "발송이 진행됐는지, 일부만 끝났는지"를 보여주는 목록. */
    public PageResponse<MockNotificationBulkJobResponse> getBulkJobs(Long policyId, Pageable pageable) {
        var page = (policyId != null)
                ? mockNotificationBulkJobRepository.findByCouponPolicyIdOrderByCreatedAtDesc(policyId, pageable)
                : mockNotificationBulkJobRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.from(page.map(MockNotificationBulkJobResponse::from));
    }
}
