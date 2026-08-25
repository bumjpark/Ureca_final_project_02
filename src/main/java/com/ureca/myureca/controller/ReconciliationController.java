package com.ureca.myureca.controller;

import com.ureca.myureca.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
}
