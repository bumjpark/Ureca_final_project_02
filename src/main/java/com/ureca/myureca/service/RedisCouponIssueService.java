package com.ureca.myureca.service;

import com.ureca.myureca.exception.CouponDuplicatedException;
import com.ureca.myureca.exception.CouponSoldOutException;
import com.ureca.myureca.support.RedisKeys;
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
        String stockKey = RedisKeys.couponStock(policyId);
        String reservedKey = RedisKeys.couponReserved(policyId);
        String issuedKey = RedisKeys.couponIssued(policyId);
        String pendingKey = RedisKeys.couponPending(policyId);
        List<String> keys = List.of(stockKey, reservedKey, issuedKey, pendingKey);
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
}
