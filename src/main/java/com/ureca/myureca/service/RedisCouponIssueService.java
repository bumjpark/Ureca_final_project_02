package com.ureca.myureca.service;

import com.ureca.myureca.exception.CouponDuplicatedException;
import com.ureca.myureca.exception.CouponSoldOutException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisCouponIssueService {
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> issueCouponScript;

    public void tryReserveCoupon(Long policyId, Long userId) {
        String stockKey = "coupon:policy:" + policyId + ":stock";
        String reservedKey = "coupon:policy:" + policyId + ":reserved";
        String issuedKey = "coupon:policy:" + policyId + ":issued";
        List<String> keys = List.of(stockKey, reservedKey, issuedKey);
        String timestamp = String.valueOf(System.currentTimeMillis());
        Long resultCode = redisTemplate.execute(
                issueCouponScript,
                keys,
                String.valueOf(userId),
                timestamp);

        if (resultCode == null) {
            throw new IllegalStateException("쿠폰 발급 처리 중 Redis 스크립트 응답이 비어있습니다.");
        }

        if (Long.valueOf(409L).equals(resultCode)) {
            throw new CouponDuplicatedException("이미 발급받았거나 접수 진행 중인 쿠폰입니다.");
        } else if (Long.valueOf(400L).equals(resultCode)) {
            throw new CouponSoldOutException("선착순 쿠폰이 모두 소진되었습니다.");
        }
    }

    public boolean isValidActiveToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        // 원자적으로 토큰을 삭제(소모)하여 재사용 및 중복 호출 방지
        Boolean deleted = redisTemplate.delete("active_token:" + token);
        return Boolean.TRUE.equals(deleted);
    }
}