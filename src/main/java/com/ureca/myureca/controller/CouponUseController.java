package com.ureca.myureca.controller;

import com.ureca.myureca.dto.request.CouponUseRequest;
import com.ureca.myureca.dto.response.CouponUseResponse;
import com.ureca.myureca.service.CouponUseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponUseController {

    private final CouponUseService couponUseService;

    @PostMapping("/{couponIssueId}/use")
    public ResponseEntity<CouponUseResponse> changeStatus(
            @PathVariable Long couponIssueId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CouponUseRequest request) {

        CouponUseResponse response =
                couponUseService.changeStatus(couponIssueId, idempotencyKey, request);
        return ResponseEntity.ok(response);
    }
}
