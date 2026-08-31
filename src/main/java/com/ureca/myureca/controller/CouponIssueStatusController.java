package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.CouponIssueStatusResponse;
import com.ureca.myureca.service.MyCouponQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발급 접수(receiptId) 상태 조회. {@code POST .../issue}가 202 ACCEPTED로 돌려준 receiptId를
 * 그대로 폴링해, DB 미반영 상태를 "정상적인 비동기 처리 중"(PENDING)과 "재처리가 필요한
 * 실패"(FAILED)로 구분해 보여준다. 미확정 상태도 200으로 응답한다 — 아직 결론이 안 났을 뿐
 * 오류가 아니기 때문이다.
 */
@RestController
@RequestMapping("/api/coupons/receipt")
@RequiredArgsConstructor
public class CouponIssueStatusController {

    private final MyCouponQueryService myCouponQueryService;

    @GetMapping("/{receiptId}")
    public ResponseEntity<CouponIssueStatusResponse> getStatus(@PathVariable String receiptId) {
        return ResponseEntity.ok(myCouponQueryService.getIssueStatusByReceiptId(receiptId));
    }
}
