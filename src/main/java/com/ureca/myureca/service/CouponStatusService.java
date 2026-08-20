package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.dto.response.CouponStatusResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.coupon.CouponPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponStatusService {

    private final CouponPolicyRepository couponPolicyRepository;
    private final StringRedisTemplate redisTemplate;

    @Transactional(readOnly = true)
    public CouponStatusResponse getCouponStatus(Long policyId) {
        // 1. DB에서 쿠폰 정책 조회
        CouponPolicy couponPolicy = couponPolicyRepository.findById(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        int totalQuantity = couponPolicy.getTotalQuantity();

        // 2. Redis에서 실시간 재고 조회 (UBM-14 키 규격과 일치)
        String stockKey = "coupon:policy:" + policyId + ":stock";
        String stockValue = redisTemplate.opsForValue().get(stockKey);

        // Redis에 재고 키가 없으면 기본 총 수량으로 방어
        int remainingQuantity = (stockValue != null) ? Integer.parseInt(stockValue) : totalQuantity;

        // 초과 발급 방어 (화면 표기용 음수 방지).
        // 정상 운영에선 Lua의 Fast-Fail 덕분에 절대 음수가 나오면 안 되므로,
        // 이 분기를 탄다는 것 자체가 NFR-1(초과발급 0건) 위반 신호다 — 화면은 0으로
        // 보여주되 반드시 경고 로그를 남겨 검증배치가 추적할 수 있게 한다.
        if (remainingQuantity < 0) {
            log.warn("Redis stock이 음수입니다 — 초과발급 의심. policyId={}, stock={}", policyId, remainingQuantity);
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
