package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기열 유저를 1초마다 주기적으로 입장시키는 백그라운드 스케줄러.
 *
 * <p>다중 서버 인스턴스 환경에서 분산 락(Redis SETNX)을 획득한 단일 인스턴스만 실행하여
 * 중복 유입(Throttling 초과)을 원천 방어한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueAdmissionScheduler {

    private final CouponPolicyRepository couponPolicyRepository;
    private final QueueAdmissionService queueAdmissionService;
    private final StringRedisTemplate redisTemplate;

    /** 1회 실행 당 입장시킬 최대 인원수 (기본: 300명/초) */
    @Value("${coupon.queue.admission-rate:300}")
    private int admissionRate;

    /**
     * 매초(기본 1000ms) 실행되는 대기열 입장 배치.
     */
    @Scheduled(fixedDelayString = "${coupon.queue.admission-interval-ms:1000}")
    public void processQueueAdmission() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 현재 오픈 진행 중인 쿠폰 정책들 조회
        List<CouponPolicy> activePolicies = couponPolicyRepository.findByDeletedAtIsNull(Pageable.unpaged())
                .getContent()
                .stream()
                .filter(policy -> !now.isBefore(policy.getOpenAt()) && (policy.getCloseAt() == null || !now.isAfter(policy.getCloseAt())))
                .toList();

        for (CouponPolicy policy : activePolicies) {
            Long policyId = policy.getId();
            String lockKey = RedisKeys.lockAdmission(policyId);

            // 2. 분산 락 획득 시도 (1초 TTL) - 다중 인스턴스 중복 실행 방어
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", 1, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                try {
                    // 재고 소진(0 이하)된 정책은 Redis 레벨에서 조기 스킵하여 불필요한 ZPOPMIN 호출 방어
                    String stockStr = redisTemplate.opsForValue().get(RedisKeys.couponStock(policyId));
                    if (stockStr != null) {
                        try {
                            if (Integer.parseInt(stockStr) <= 0) {
                                log.debug("재고 소진 정책 입장 처리 스킵. policyId={}", policyId);
                                continue;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    queueAdmissionService.admitUsers(policyId, admissionRate);
                } catch (Exception e) {
                    log.error("대기열 입장 스케줄러 실행 중 오류 발생. policyId={}", policyId, e);
                }
            } else {
                log.debug("다른 서버 인스턴스에서 이미 입장 배치를 실행 중입니다. policyId={}", policyId);
            }
        }
    }
}
