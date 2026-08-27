package com.ureca.myureca.service;

import com.ureca.myureca.dto.response.ComponentHealthResponse;
import com.ureca.myureca.dto.response.HealthResponse;
import com.ureca.myureca.dto.response.RedisRecoverResponse;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.exception.VerificationNotAllowedException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 오픈 중인 정책 중 Redis {@code stock} 키가 없는 것을 찾아 자동으로
 * {@link RedisRecoveryService#recover}를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAutoRecoveryScheduler {

    private final CouponPolicyRepository couponPolicyRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisRecoveryService redisRecoveryService;
    private final HealthCheckService healthCheckService;

    @Scheduled(fixedDelayString = "${coupon.redis-recovery.auto-interval-ms:5000}")
    public void recoverMissingStock() {
        if (!isRedisUp()) {
            log.debug("[RedisAutoRecovery] Redis가 아직 DOWN 상태라 이번 틱은 건너뜁니다.");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<CouponPolicy> activePolicies = couponPolicyRepository.findByDeletedAtIsNull(Pageable.unpaged())
                .getContent()
                .stream()
                .filter(policy -> !now.isBefore(policy.getOpenAt())
                        && (policy.getCloseAt() == null || !now.isAfter(policy.getCloseAt())))
                .toList();

        for (CouponPolicy policy : activePolicies) {
            recoverIfStockMissing(policy.getId());
        }
    }

    private boolean isRedisUp() {
        HealthResponse health = healthCheckService.check();
        ComponentHealthResponse redisHealth = health.components().get("redis");
        return redisHealth != null && redisHealth.isUp();
    }

    private void recoverIfStockMissing(Long policyId) {
        try {
            Boolean exists = redisTemplate.hasKey(RedisKeys.couponStock(policyId));
            if (Boolean.TRUE.equals(exists)) {
                return;
            }

            RedisRecoverResponse response = redisRecoveryService.recover(policyId);
            log.info("[RedisAutoRecovery] policyId={} stock 키 부재 감지 -> 자동 복구 완료. "
                            + "issuedCount={}, remainingStock={}",
                    policyId, response.issuedCount(), response.remainingStock());
        } catch (VerificationNotAllowedException e) {
            // lag != 0 이거나 다른 인스턴스가 이미 복구를 진행 중인 경우 - 다음 틱에 자동으로
            // 다시 시도되므로 조용히(debug) 넘어간다.
            log.debug("[RedisAutoRecovery] policyId={} 자동 복구 보류: {}", policyId, e.getMessage());
        } catch (Exception e) {
            log.error("[RedisAutoRecovery] policyId={} 자동 복구 중 예상 못한 오류", policyId, e);
        }
    }
}
