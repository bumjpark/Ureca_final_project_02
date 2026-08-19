package com.ureca.myureca.service;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.dto.response.CouponIssueResponse;
import com.ureca.myureca.exception.InvalidTokenException;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final RedisCouponIssueService redisCouponIssueService;
    private final KafkaCouponEventProducer kafkaCouponEventProducer;

    public CouponIssueResponse issueCoupon(Long policyId, Long userId, String activeToken) {
        // 1. 대기열 토큰 검증
        if (!redisCouponIssueService.isValidActiveToken(activeToken)) {
            throw new InvalidTokenException("유효하지 않거나 만료된 대기열 토큰입니다.");
        }

        // 2. Redis Lua 원자적 판별 (중복 409 / 품절 400)
        redisCouponIssueService.tryReserveCoupon(policyId, userId);

        // 3. 접수증 생성 및 Kafka 비동기 이벤트 전송
        String receiptId = "rcpt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        CouponIssuedEvent event = new CouponIssuedEvent(policyId, userId, receiptId, LocalDateTime.now());
        kafkaCouponEventProducer.publishCouponIssuedEvent(event);

        // 4. 202 ACCEPTED 즉시 응답
        return CouponIssueResponse.accepted(receiptId);
    }
}
