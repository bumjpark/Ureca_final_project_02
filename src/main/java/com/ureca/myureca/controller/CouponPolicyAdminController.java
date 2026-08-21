package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.CouponPolicyResponse;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.service.CouponPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/coupon-policies")
@RequiredArgsConstructor
public class CouponPolicyAdminController {

    private final CouponPolicyService couponPolicyService;

    @GetMapping
    public PageResponse<CouponPolicyResponse> getCouponPolicies(
            @PageableDefault(size = 10) Pageable pageable) {
        return couponPolicyService.getCouponPolicies(pageable);
    }

    @GetMapping("/{policyId}")
    public CouponPolicyResponse getCouponPolicy(@PathVariable Long policyId) {
        return couponPolicyService.getCouponPolicy(policyId);
    }
}
