package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.dto.response.CouponStatusResponse;
import com.ureca.myureca.repository.coupon.CouponPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponStatusService {

    private final CouponPolicyRepository couponPolicyRepository;
    private final StringRedisTemplate redisTemplate;

    @Transactional(readOnly = true)
    public CouponStatusResponse getCouponStatus(Long policyId) {
        // 1. DB에서 쿠폰 정책 조회
        CouponPolicy couponPolicy = couponPolicyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰 정책입니다. ID: " + policyId));

        int totalQuantity = couponPolicy.getTotalQuantity();

        // 2. Redis에서 실시간 재고 조회 (UBM-14 키 규격과 일치)
        String stockKey = "coupon:policy:" + policyId + ":stock";
        String stockValue = redisTemplate.opsForValue().get(stockKey);

        // Redis에 재고 키가 없으면 기본 총 수량으로 방어
        int remainingQuantity = (stockValue != null) ? Integer.parseInt(stockValue) : totalQuantity;

        // 초과 발급 방어 (화면 표기용 음수 방지)
        if (remainingQuantity < 0) {
            remainingQuantity = 0;
        }

        // 3. 발급 완료 수량 계산
        int issuedQuantity = totalQuantity - remainingQuantity;

        // 4. 발급률 계산 (소수점 둘째 자리까지 반올림)
        double issueRate = (totalQuantity == 0)
                ? 0.0
                : Math.round(((double) issuedQuantity / totalQuantity) * 10000.0) / 100.0;

        return new CouponStatusResponse(
                policyId,
                totalQuantity,
                issuedQuantity,
                remainingQuantity,
                issueRate
        );
    }
}