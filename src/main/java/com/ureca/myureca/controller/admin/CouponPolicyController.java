package com.ureca.myureca.controller.admin;

import com.ureca.myureca.dto.coupon.CouponPolicyCreateRequest;
import com.ureca.myureca.dto.coupon.CouponPolicyResponse;
import com.ureca.myureca.service.coupon.CouponPolicyService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/coupon-policies")
public class CouponPolicyController {

    private final CouponPolicyService couponPolicyService;

    public CouponPolicyController(CouponPolicyService couponPolicyService) {
        this.couponPolicyService = couponPolicyService;
    }

    @PostMapping
    public ResponseEntity<CouponPolicyResponse> createCouponPolicy(
            @Valid @RequestBody CouponPolicyCreateRequest request
    ) {
        CouponPolicyResponse response = couponPolicyService.createCouponPolicy(request);
        URI location = URI.create("/api/admin/coupon-policies/" + response.id());
        return ResponseEntity.created(location).body(response);
    }
}
