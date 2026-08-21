package com.ureca.myureca.controller;

import com.ureca.myureca.dto.CouponPolicyCreateRequest;
import com.ureca.myureca.dto.CouponPolicyResponse;
import com.ureca.myureca.service.CouponPolicyService;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/coupon-policies")
@RequiredArgsConstructor
public class CouponPolicyController {

    private final CouponPolicyService couponPolicyService;

    @PostMapping
    public ResponseEntity<CouponPolicyResponse> createCouponPolicy(
            @Valid @RequestBody CouponPolicyCreateRequest request
    ) {
        CouponPolicyResponse response = couponPolicyService.createCouponPolicy(request);
        URI location = URI.create("/api/admin/coupon-policies/" + response.id());
        return ResponseEntity.created(location).body(response);
    }
}
