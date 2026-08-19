package com.ureca.myureca.service.coupon;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.coupon.CouponPolicyCreateRequest;
import com.ureca.myureca.dto.coupon.CouponPolicyResponse;
import com.ureca.myureca.exception.InvalidCouponPolicyException;
import com.ureca.myureca.repository.coupon.CouponPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CouponPolicyService {

    private static final int MIN_RATE_DISCOUNT_VALUE = 1;
    private static final int MAX_RATE_DISCOUNT_VALUE = 100;

    private final CouponPolicyRepository couponPolicyRepository;

    public CouponPolicyService(CouponPolicyRepository couponPolicyRepository) {
        this.couponPolicyRepository = couponPolicyRepository;
    }

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
