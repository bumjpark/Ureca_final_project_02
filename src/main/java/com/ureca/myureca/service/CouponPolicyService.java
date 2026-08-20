package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.dto.CouponPolicyResponse;
import com.ureca.myureca.dto.PageResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponPolicyService {

    private final CouponPolicyRepository couponPolicyRepository;

    public PageResponse<CouponPolicyResponse> getCouponPolicies(Pageable pageable) {
        Page<CouponPolicyResponse> page = couponPolicyRepository.findByDeletedAtIsNull(pageable)
                .map(CouponPolicyResponse::from);
        return PageResponse.from(page);
    }

    public CouponPolicyResponse getCouponPolicy(Long policyId) {
        CouponPolicy couponPolicy = couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));
        return CouponPolicyResponse.from(couponPolicy);
    }
}
