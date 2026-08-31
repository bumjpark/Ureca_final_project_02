package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.CouponIssuanceMetricsResponse;
import com.ureca.myureca.dto.response.CouponStatusResponse;
import com.ureca.myureca.service.CouponStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupon-policies")
public class CouponStatusController {

    // 그래프 창 상한(초) — 파라미터로 과도하게 큰 범위를 요청해도 매 초 버킷 생성 루프가 무한정
    // 늘어나지 않도록 방어(10분이면 초 단위 실시간 모니터링 용도로 충분히 넉넉하다).
    private static final int MAX_TIMELINE_SECONDS = 600;

    private final CouponStatusService couponStatusService;

    @GetMapping("/{policyId}/status")
    public ResponseEntity<CouponStatusResponse> getCouponStatus(
            @PathVariable("policyId") Long policyId
    ) {
        CouponStatusResponse response = couponStatusService.getCouponStatus(policyId);
        return ResponseEntity.ok(response);
    }

    /** 실시간 발급 그래프(1초 단위) + 사용/만료 건수, 초당 발급 속도. */
    @GetMapping("/{policyId}/status/metrics")
    public ResponseEntity<CouponIssuanceMetricsResponse> getIssuanceMetrics(
            @PathVariable("policyId") Long policyId,
            @RequestParam(required = false, defaultValue = "120") int seconds
    ) {
        int boundedSeconds = Math.max(1, Math.min(seconds, MAX_TIMELINE_SECONDS));
        return ResponseEntity.ok(couponStatusService.getIssuanceMetrics(policyId, boundedSeconds));
    }
}