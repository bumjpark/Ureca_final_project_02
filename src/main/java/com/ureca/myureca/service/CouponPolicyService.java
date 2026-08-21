package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.dto.CouponPolicyResponse;
import com.ureca.myureca.dto.PageResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.CouponPolicyCreateRequest;
import com.ureca.myureca.dto.CouponPolicyResponse;
import com.ureca.myureca.exception.InvalidCouponPolicyException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import lombok.RequiredArgsConstructor;
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
@Transactional
@RequiredArgsConstructor
public class CouponPolicyService {

    private static final int MIN_RATE_DISCOUNT_VALUE = 1;
    private static final int MAX_RATE_DISCOUNT_VALUE = 100;

    private final CouponPolicyRepository couponPolicyRepository;

    public CouponPolicyResponse createCouponPolicy(CouponPolicyCreateRequest request) {
        validateBusinessRules(request);

        CouponPolicy couponPolicy = new CouponPolicy(
                request.title(),
                request.couponType(),
                request.discountValue(),
                request.totalQuantity(),
                request.openAt(),
                request.closeAt()
        );

        CouponPolicy saved = couponPolicyRepository.save(couponPolicy);
        return CouponPolicyResponse.from(saved);
    }

    private void validateBusinessRules(CouponPolicyCreateRequest request) {
        if (request.closeAt() != null && !request.closeAt().isAfter(request.openAt())) {
            throw new InvalidCouponPolicyException("closeAt은 openAt보다 이후여야 합니다");
        }

        // RATE(정률 할인)는 할인율(%) 값이라고 가정 — 명세에 근거 문서 없음, 1~100으로 제한
        if (request.couponType() == CouponType.RATE) {
            int discountValue = request.discountValue();
            if (discountValue < MIN_RATE_DISCOUNT_VALUE || discountValue > MAX_RATE_DISCOUNT_VALUE) {
                throw new InvalidCouponPolicyException(
                        "RATE 타입의 discountValue는 %d~%d 사이여야 합니다"
                                .formatted(MIN_RATE_DISCOUNT_VALUE, MAX_RATE_DISCOUNT_VALUE)
                );
            }
        }
    }
}
