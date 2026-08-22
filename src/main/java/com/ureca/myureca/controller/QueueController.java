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
}
