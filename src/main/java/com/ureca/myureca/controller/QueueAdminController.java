package com.ureca.myureca.controller;

import com.ureca.myureca.dto.request.QueueLimitUpdateRequest;
import com.ureca.myureca.dto.response.QueueLimitResponse;
import com.ureca.myureca.service.QueueLimitAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기열 관리자 전용 제어 API.
 */
@RestController
@RequestMapping("/api/admin/queue")
@RequiredArgsConstructor
public class QueueAdminController {

    private final QueueLimitAdminService queueLimitAdminService;

    /**
     * 대기열 처리 Limit(초당 통과 정원) 동적 조정.
     *
     * <p>트래픽 급증 또는 DB 부하 상황에 따라 서버 재부팅 없이 실시간으로 대기열 처리량을 조절한다.
     *
     * @param request 수정 요청 바디 (limit: 1 ~ 50,000, policyId: 선택)
     * @return 200 OK + {@link QueueLimitResponse}
     */
    @PatchMapping("/limit")
    public QueueLimitResponse updateLimit(@Valid @RequestBody QueueLimitUpdateRequest request) {
        return queueLimitAdminService.updateLimit(request);
    }
}
