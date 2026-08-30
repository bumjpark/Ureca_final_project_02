package com.ureca.myureca.controller;

import com.ureca.myureca.dto.request.KakaoNotificationRequest;
import com.ureca.myureca.dto.request.MockNotificationBulkRequest;
import com.ureca.myureca.dto.response.KakaoNotificationResponse;
import com.ureca.myureca.dto.response.MockNotificationBulkJobResponse;
import com.ureca.myureca.dto.response.MockNotificationBulkResponse;
import com.ureca.myureca.dto.response.MockNotificationLogResponse;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.service.MockNotificationService;
import jakarta.validation.Valid;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mock/notifications")
public class MockNotificationController {

    private static final long ASYNC_TIMEOUT_MS = 5000;

    private final MockNotificationService mockNotificationService;
    private final AsyncTaskExecutor mockNotificationAsyncTaskExecutor;

    /**
     * 단건 발송. Tomcat 요청 스레드는 여기서 바로 반환되고, 실제 지연 시뮬레이션은
     * mockNotificationAsyncTaskExecutor(가상 스레드)에서 실행된다.
     */
    @PostMapping("/kakao")
    public WebAsyncTask<ResponseEntity<KakaoNotificationResponse>> sendKakaoNotification(
            @Valid @RequestBody KakaoNotificationRequest request,
            @RequestParam(required = false, defaultValue = "false") boolean simulateFailure) {

        Callable<ResponseEntity<KakaoNotificationResponse>> task =
                () -> ResponseEntity.ok(mockNotificationService.send(request, simulateFailure));

        return new WebAsyncTask<>(ASYNC_TIMEOUT_MS, mockNotificationAsyncTaskExecutor, task);
    }

    /**
     * 정책별 일괄 발송 — 그 정책으로 쿠폰을 발급받은 유저 전원에게 보낸다.
     * 대상자가 많으면 순차 발송이 오래 걸릴 수 있어 실제 발송은 서비스 내부에서 비동기로
     * 흘려보내고, 여기서는 대상자 수만 확인해 즉시 202로 응답한다. 결과는
     * {@code GET .../logs?policyId=}로 확인한다.
     */
    @PostMapping("/kakao/bulk")
    public ResponseEntity<MockNotificationBulkResponse> sendBulk(@Valid @RequestBody MockNotificationBulkRequest request) {
        MockNotificationService.BulkSendResult result = mockNotificationService.sendBulkByPolicy(
                request.policyId(), request.templateId(), request.message());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new MockNotificationBulkResponse(result.jobId(), request.policyId(), result.targetCount()));
    }

    /** 발송 이력 조회. policyId를 주면 그 정책(일괄 발송) 대상만, 생략하면 전체(단건 포함). */
    @GetMapping("/logs")
    public PageResponse<MockNotificationLogResponse> getLogs(
            @RequestParam(required = false) Long policyId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return mockNotificationService.getLogs(policyId, pageable);
    }

    /**
     * 정책별 일괄 발송 진행 상태 조회 — "발송이 진행됐는지, 일부만 끝났는지"를 확인하는 용도.
     * policyId를 주면 그 정책의 일괄 발송 이력만, 생략하면 전체 정책의 이력을 최신순으로 준다.
     */
    @GetMapping("/bulk-jobs")
    public PageResponse<MockNotificationBulkJobResponse> getBulkJobs(
            @RequestParam(required = false) Long policyId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return mockNotificationService.getBulkJobs(policyId, pageable);
    }
}
