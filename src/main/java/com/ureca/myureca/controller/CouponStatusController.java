package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.CouponStatusResponse;
import com.ureca.myureca.service.CouponStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupon-policies")
public class CouponStatusController {

    private final CouponStatusService couponStatusService;

    @GetMapping("/{policyId}/status")
    public ResponseEntity<CouponStatusResponse> getCouponStatus(
            @PathVariable("policyId") Long policyId
    ) {
        CouponStatusResponse response = couponStatusService.getCouponStatus(policyId);
        return ResponseEntity.ok(response);
    }
}