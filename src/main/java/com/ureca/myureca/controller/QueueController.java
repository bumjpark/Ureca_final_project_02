package com.ureca.myureca.controller;

import com.ureca.myureca.dto.request.QueueJoinRequest;
import com.ureca.myureca.dto.response.QueueJoinResponse;
import com.ureca.myureca.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선착순 쿠폰 대기열 관련 API.
 *
 * <p>대기열은 Lua Script + Redis ZSET 기반의 원자적 처리로 구현된다.
 * 클라이언트는 WAITING 상태를 받으면 {@code GET /api/queue/status}를 폴링하여 입장 여부를 확인한다.
 */
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final com.ureca.myureca.service.QueueSseService queueSseService;

    /**
     * 대기열 등록.
     *
     * <p>성공 시 {@code WAITING}(순번 대기) 또는 {@code ADMITTED}(즉시 입장) 상태를 반환한다.
     * {@code ADMITTED} 상태이면 {@code activeToken}을 {@code POST /api/coupon-policies/{policyId}/issue}
     * 요청 헤더 {@code X-Active-Token}에 포함해야 한다.
     *
     * @return 200 OK + {@link QueueJoinResponse}
     */
    @PostMapping("/join")
    public QueueJoinResponse joinQueue(@Valid @RequestBody QueueJoinRequest request) {
        return queueService.joinQueue(request);
    }

    /**
     * 대기열 상태 조회 (폴링).
     *
     * <p>대기 중인 클라이언트가 주기적으로 본인의 대기 순번 및 입장 가능 여부를 확인한다.
     * {@code retryAfterSeconds} 값을 활용하여 폴링 주기를 동적으로 조절한다.
     *
     * @return 200 OK + {@link com.ureca.myureca.dto.response.QueueStatusResponse}
     */
    @org.springframework.web.bind.annotation.GetMapping("/status")
    public com.ureca.myureca.dto.response.QueueStatusResponse getQueueStatus(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive @org.springframework.web.bind.annotation.RequestParam("policyId") Long policyId,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive @org.springframework.web.bind.annotation.RequestParam("userId") Long userId
    ) {
        return queueService.getQueueStatus(policyId, userId);
    }

    /**
     * 대기열 상태 실시간 SSE 스트림.
     *
     * <p>클라이언트는 폴링 대신 이 스트림을 열어두고,
     * 서버로부터 순번 갱신 및 {@code activeToken}(입장권)을 실시간 푸시(Push)받는다.
     */
    @org.springframework.web.bind.annotation.GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamQueueStatus(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive @org.springframework.web.bind.annotation.RequestParam("policyId") Long policyId,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive @org.springframework.web.bind.annotation.RequestParam("userId") Long userId
    ) {
        return queueSseService.connect(policyId, userId);
    }
}
