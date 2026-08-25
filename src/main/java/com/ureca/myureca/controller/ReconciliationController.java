package com.ureca.myureca.controller;

import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.dto.response.ReconciliationLogResponse;
import com.ureca.myureca.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    /**
     * 정합성 복구 - 수동 재처리 실행
     */
    @PostMapping("/retry")
    public ResponseEntity<?> retry(@RequestParam(required = false) Long logId) {
        if (logId != null) {
            return ResponseEntity.accepted().body(reconciliationService.retryOne(logId));
        }
        return ResponseEntity.accepted().body(reconciliationService.retryAll());
    }

    /**
     * 정합성 복구 - 재처리 이력 조회. type/status는 둘 다 선택 필터.
     */
    @GetMapping("/logs")
    public PageResponse<ReconciliationLogResponse> getLogs(
            @RequestParam(required = false) ReconciliationType type,
            @RequestParam(required = false) ReconciliationStatus status,
            @PageableDefault(size = 10) Pageable pageable
    ) {
        return reconciliationService.getReconciliationLogs(type, status, pageable);
    }
}
