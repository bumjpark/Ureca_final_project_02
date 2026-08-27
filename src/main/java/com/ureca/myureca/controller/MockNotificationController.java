package com.ureca.myureca.controller;

import com.ureca.myureca.dto.request.KakaoNotificationRequest;
import com.ureca.myureca.dto.response.KakaoNotificationResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;


@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mock/notifications")
public class MockNotificationController {

    private static final long ASYNC_TIMEOUT_MS = 5000;

    private final AsyncTaskExecutor mockNotificationAsyncTaskExecutor;

    @Value("${mock.kakao.min-latency-ms:50}")
    private long minLatencyMs;

    @Value("${mock.kakao.max-latency-ms:150}")
    private long maxLatencyMs;

    @Value("${mock.kakao.failure-rate:0}")
    private double failureRate;

    @PostMapping("/kakao")
    public WebAsyncTask<ResponseEntity<KakaoNotificationResponse>> sendKakaoNotification(
            @Valid @RequestBody KakaoNotificationRequest request,
            @RequestParam(required = false, defaultValue = "false") boolean simulateFailure) {

        Callable<ResponseEntity<KakaoNotificationResponse>> task =
                () -> buildResponse(request, simulateFailure);

        // Tomcat 요청 스레드는 여기서 바로 반환되고, task는 mockNotificationAsyncTaskExecutor
        // (가상 스레드)에서 실행된다 — 지연 시뮬레이션이 컨테이너 스레드 풀을 갉아먹지 않는다.
        return new WebAsyncTask<>(ASYNC_TIMEOUT_MS, mockNotificationAsyncTaskExecutor, task);
    }

    private ResponseEntity<KakaoNotificationResponse> buildResponse(
            KakaoNotificationRequest request, boolean simulateFailure) {
        simulateNetworkLatency();

        String messageId = "mock-msg-" + UUID.randomUUID();
        boolean failed = simulateFailure || ThreadLocalRandom.current().nextDouble() < failureRate;

        if (failed) {
            log.info("[MockKakao] 발송 실패(mock) - userId={}, templateId={}, messageId={}",
                    request.userId(), request.templateId(), messageId);
            return ResponseEntity.ok(KakaoNotificationResponse.failed(messageId));
        }

        log.info("[MockKakao] 발송 성공(mock) - userId={}, templateId={}, messageId={}",
                request.userId(), request.templateId(), messageId);
        return ResponseEntity.ok(KakaoNotificationResponse.sent(messageId));
    }

    private void simulateNetworkLatency() {
        long delay = ThreadLocalRandom.current().nextLong(minLatencyMs, maxLatencyMs + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
