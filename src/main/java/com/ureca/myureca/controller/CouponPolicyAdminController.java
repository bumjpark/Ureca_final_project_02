package com.ureca.myureca.controller;

import com.ureca.myureca.dto.request.CouponPolicyUpdateRequest;
import com.ureca.myureca.dto.response.CouponPolicyExpirationResponse;
import com.ureca.myureca.dto.response.CouponPolicyResponse;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.service.CouponExpirationService;
import com.ureca.myureca.service.CouponPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/coupon-policies")
@RequiredArgsConstructor
public class CouponPolicyAdminController {

    private final CouponPolicyService couponPolicyService;
    private final CouponExpirationService couponExpirationService;

    @GetMapping
    public PageResponse<CouponPolicyResponse> getCouponPolicies(
            @PageableDefault(size = 10) Pageable pageable) {
        return couponPolicyService.getCouponPolicies(pageable);
    }

    @GetMapping("/{policyId}")
    public CouponPolicyResponse getCouponPolicy(@PathVariable Long policyId) {
        return couponPolicyService.getCouponPolicy(policyId);
    }

    @PatchMapping("/{policyId}")
    public CouponPolicyResponse updateCouponPolicy(
            @PathVariable Long policyId,
            @Valid @RequestBody CouponPolicyUpdateRequest request) {
        return couponPolicyService.updateCouponPolicy(policyId, request);
    }

    @DeleteMapping("/{policyId}")
    public ResponseEntity<Void> deleteCouponPolicy(@PathVariable Long policyId) {
        couponPolicyService.deleteCouponPolicy(policyId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 특정 쿠폰 정책 및 소속된 발급 쿠폰들을 청크 단위로 EXPIRED 상태로 만료 처리한다.
     */
    @PostMapping("/{policyId}/expire")
    public ResponseEntity<CouponPolicyExpirationResponse> expireCouponPolicy(
            @PathVariable Long policyId,
            @RequestParam(required = false, defaultValue = "5000") int chunkSize) {
        CouponPolicyExpirationResponse response =
                couponExpirationService.expireCouponsByPolicyId(policyId, chunkSize);
        return ResponseEntity.ok(response);
    }

    /**
     * 마감 기한이 지난 전체 만료 대상 쿠폰 정책 및 발급 쿠폰들을 일괄 청크 만료 처리한다.
     */
    @PostMapping("/expire")
    public ResponseEntity<CouponPolicyExpirationResponse> expireAllCouponPolicies(
            @RequestParam(required = false, defaultValue = "5000") int chunkSize) {
        CouponPolicyExpirationResponse response =
                couponExpirationService.expireAllCoupons(chunkSize);
        return ResponseEntity.ok(response);
    }
}
