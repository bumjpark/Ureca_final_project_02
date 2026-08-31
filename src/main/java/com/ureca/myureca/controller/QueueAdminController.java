package com.ureca.myureca.controller;

import com.ureca.myureca.dto.request.QueueLimitUpdateRequest;
import com.ureca.myureca.dto.response.QueueAdminStatusResponse;
import com.ureca.myureca.dto.response.QueueLimitResponse;
import com.ureca.myureca.service.QueueLimitAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * 특정 정책의 현재 대기 인원 + 적용 중인 처리 속도 조회. 부하테스트 중 대기열이 실제로
     * 줄어드는지 화면에서 확인하기 위한 조회 전용 엔드포인트.
     */
    @GetMapping("/status")
    public QueueAdminStatusResponse getStatus(@NotNull @Positive @RequestParam Long policyId) {
        return queueLimitAdminService.getStatus(policyId);
    }
}
