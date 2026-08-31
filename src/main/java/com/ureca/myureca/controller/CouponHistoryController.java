package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.CouponHistoryResponse;
import com.ureca.myureca.service.CouponHistoryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupons")
public class CouponHistoryController {

    private final CouponHistoryService couponHistoryService;

    @GetMapping("/{couponIssueId}/history")
    public ResponseEntity<List<CouponHistoryResponse>> getCouponHistory(
            @PathVariable("couponIssueId") Long couponIssueId) {
        List<CouponHistoryResponse> response = couponHistoryService.getCouponHistory(couponIssueId);
        return ResponseEntity.ok(response);
    }
}
