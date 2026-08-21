package com.ureca.myureca.controller;

import com.ureca.myureca.dto.request.CouponIssueRequest;
import com.ureca.myureca.dto.response.CouponIssueResponse;
import com.ureca.myureca.service.CouponIssueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupon-policies")
@RequiredArgsConstructor
public class CouponIssueController {

    private final CouponIssueService couponIssueService;

    @PostMapping("/{policyId}/issue")
    public ResponseEntity<CouponIssueResponse> issueCoupon(
            @PathVariable Long policyId,
            @RequestHeader(value = "X-Active-Token", required = false) String activeToken,
            @Valid @RequestBody CouponIssueRequest request) {
        CouponIssueResponse response = couponIssueService.issueCoupon(policyId, request.userId(), activeToken);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
