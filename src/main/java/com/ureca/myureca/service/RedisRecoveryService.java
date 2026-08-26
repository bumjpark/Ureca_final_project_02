package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.dto.response.RedisRecoverResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.VerificationNotAllowedException;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.KafkaConsumerLagChecker;
import com.ureca.myureca.support.RedisKeys;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 완전 유실 복구 (E). {@code POST /api/coupons/{eventId}/recover}
 *
 * <p><b>팀 아키텍처 로드맵 변경으로 재설계됨.</b> 이전 버전은 이 서비스가 직접 Kafka
 * 토픽을 처음부터 읽어 DB에 반영하는 "1단계"까지 떠맡았다 — 그때는 상시 Kafka Consumer가
 * 없었기 때문이다. 이제 로드맵상 상시 Consumer가 그 역할을 전담하므로 그 로직은 전부
 * 제거했다. 대신 "그 상시 Consumer가 지금 얼마나 밀려있는가(lag)"를 확인해서, 밀려있으면
 * (lag != 0) DB가 아직 최신 상태가 아니라는 뜻이므로 Redis를 절대 건드리지 않고 즉시
 * 실패한다 — 부분적으로만 따라잡은 DB 상태로 Redis를 잘못 재구성해버리는 걸 막기 위함이다.</p>
 *
 * <p>lag == 0으로 확인된 뒤에만 DB(source of truth)를 기준으로 정책의 Redis
 * stock/issued 상태를 처음부터 다시 만든다.</p>
 *
 * <p><b>=== 팀원의 코드가 따로필요! ===</b> 관리자 인증/인가가 아직 이 프로젝트 전체에
 * 없어서, 이 엔드포인트도 지금은 URL만 알면 누구나 호출 가능한 상태다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisRecoveryService {

    private static final String TOPIC = "coupon-issued-events";
    private static final String CONSUMER_GROUP_ID = "coupon-service";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final int REDIS_SADD_BATCH_SIZE = 500;

    private final CouponIssueRepository couponIssueRepository;
    private final CouponPolicyRepository couponPolicyRepository;
    private final StringRedisTemplate redisTemplate;
    private final KafkaConsumerLagChecker lagChecker;

    public RedisRecoverResponse recover(Long policyId) {
        CouponPolicy policy = couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        String lockKey = lockKey(policyId);
        if (!tryAcquireLock(lockKey)) {
            throw new VerificationNotAllowedException(
                    "이미 이 정책에 대한 복구 작업이 진행 중입니다. 잠시 후 다시 시도해 주세요.");
        }
        try {
            long lag = lagChecker.getLag(TOPIC, CONSUMER_GROUP_ID);
            if (lag != 0) {
                String reason = (lag < 0)
                        ? "Kafka consumer lag를 확인할 수 없어 복구를 진행하지 않습니다 (컨슈머/브로커 상태 확인 필요)."
                        : "Kafka consumer가 아직 " + lag + "건을 처리하지 못했습니다. "
                                + "DB가 최신 상태가 아니라 지금은 Redis를 재구성할 수 없습니다. 잠시 후 다시 시도해 주세요.";
                throw new VerificationNotAllowedException(reason);
            }

            return syncPolicyRedisState(policy, lag);
        } finally {
            releaseLock(lockKey);
        }
    }

    RedisRecoverResponse syncPolicyRedisState(CouponPolicy policy, long kafkaLag) {
        Long policyId = policy.getId();
        long issuedCount = couponIssueRepository.countByCouponPolicyId(policyId);
        List<Long> issuedUserIds = couponIssueRepository.findUserIdsByCouponPolicyId(policyId);
        int remainingStock = (int) Math.max(0, policy.getTotalQuantity() - issuedCount);

        String stockKey = RedisKeys.couponStock(policyId);
        String reservedKey = RedisKeys.couponReserved(policyId);
        String issuedKey = RedisKeys.couponIssued(policyId);

        // Redis가 완전히 유실된 상황이므로, 아직 ISSUED로 확정되지 못했던 예약(reserved)은
        // 근거를 잃은 것으로 보고 비운다.
        redisTemplate.delete(reservedKey);
        redisTemplate.delete(issuedKey);

        if (!issuedUserIds.isEmpty()) {
            String[] members = issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);
            for (int i = 0; i < members.length; i += REDIS_SADD_BATCH_SIZE) {
                String[] batch = Arrays.copyOfRange(members, i, Math.min(i + REDIS_SADD_BATCH_SIZE, members.length));
                redisTemplate.opsForSet().add(issuedKey, batch);
            }
        }

        redisTemplate.opsForValue().set(stockKey, String.valueOf(remainingStock));

        log.info("[RedisRecovery] policyId={} 복구 완료 - issuedCount={}, remainingStock={}",
                policyId, issuedCount, remainingStock);

        return RedisRecoverResponse.success(policyId, policy.getTotalQuantity(), issuedCount, remainingStock, kafkaLag);
    }

    private String lockKey(Long policyId) {
        return "recover:lock:" + policyId;
    }

    private boolean tryAcquireLock(String lockKey) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, String.valueOf(System.currentTimeMillis()), LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock(String lockKey) {
        redisTemplate.delete(lockKey);
    }
}
