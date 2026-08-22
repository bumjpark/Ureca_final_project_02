package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.request.CouponPolicyCreateRequest;
import com.ureca.myureca.dto.request.CouponPolicyUpdateRequest;
import com.ureca.myureca.dto.response.CouponPolicyResponse;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.InvalidCouponPolicyException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponPolicyService {

    private static final int MIN_RATE_DISCOUNT_VALUE = 1;
    private static final int MAX_RATE_DISCOUNT_VALUE = 100;

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

    @Transactional
    public CouponPolicyResponse createCouponPolicy(CouponPolicyCreateRequest request) {
        validateBusinessRules(request.openAt(), request.closeAt(), request.couponType(), request.discountValue());

        CouponPolicy couponPolicy = new CouponPolicy(
                request.title(),
                request.couponType(),
                request.discountValue(),
                request.totalQuantity(),
                request.openAt(),
                request.closeAt());

        CouponPolicy saved = couponPolicyRepository.save(couponPolicy);
        return CouponPolicyResponse.from(saved);
    }

    @Transactional
    public CouponPolicyResponse updateCouponPolicy(Long policyId, CouponPolicyUpdateRequest request) {
        CouponPolicy couponPolicy = couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        if (!LocalDateTime.now().isBefore(couponPolicy.getOpenAt())) {
            throw new InvalidCouponPolicyException("오픈 시각 이후에는 쿠폰 정책을 수정할 수 없습니다");
        }

        validateBusinessRules(request.openAt(), request.closeAt(), request.couponType(), request.discountValue());

        couponPolicy.update(
                request.title(),
                request.couponType(),
                request.discountValue(),
                request.totalQuantity(),
                request.openAt(),
                request.closeAt());

        return CouponPolicyResponse.from(couponPolicy);
    }

    private void validateBusinessRules(
            LocalDateTime openAt,
            LocalDateTime closeAt,
            CouponType couponType,
            int discountValue) {
        if (closeAt != null && !closeAt.isAfter(openAt)) {
            throw new InvalidCouponPolicyException("closeAt은 openAt보다 이후여야 합니다");
        }

        // RATE(정률 할인)는 할인율(%) 값이라고 가정 — 명세에 근거 문서 없음, 1~100으로 제한
        if (couponType == CouponType.RATE) {
            if (discountValue < MIN_RATE_DISCOUNT_VALUE || discountValue > MAX_RATE_DISCOUNT_VALUE) {
                throw new InvalidCouponPolicyException(
                        "RATE 타입의 discountValue는 %d~%d 사이여야 합니다"
                                .formatted(MIN_RATE_DISCOUNT_VALUE, MAX_RATE_DISCOUNT_VALUE));
            }
        }
    }
}