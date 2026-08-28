package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.CouponDetailResponse;
import com.ureca.myureca.service.MyCouponQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponDetailController {

    private final MyCouponQueryService myCouponQueryService;

    @GetMapping("/{couponIssueId}")
    public ResponseEntity<CouponDetailResponse> getCouponDetail(
            @PathVariable Long couponIssueId,
            @RequestParam Long userId) {

        return ResponseEntity.ok(myCouponQueryService.getCouponDetail(couponIssueId, userId));
    }
}
