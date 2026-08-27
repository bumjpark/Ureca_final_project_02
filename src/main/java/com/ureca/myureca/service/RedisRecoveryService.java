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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisRecoveryService {

    private static final String TOPIC = "coupon-issued-events";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration LOCK_RENEW_INTERVAL = Duration.ofSeconds(10);
    private static final int REDIS_SADD_BATCH_SIZE = 500;

    // KafkaCouponEventConsumer(@KafkaListener)가 실제로 구독할 때 쓰는 group-id와 반드시 같아야
    // lag가 의미를 갖는다. 예전에 이 값이 여기서 "coupon-service"로 하드코딩돼 있었는데, 실제
    // 컨슈머는 이 프로퍼티(coupon.kafka.consumer.group-id, 기본값 coupon-issue-consumer-group)를
    // 쓰고 있어서 서로 달랐다 — 그 상태에서는 존재하지도 않는 컨슈머 그룹의 lag를 재는 셈이라
    // committed offset이 항상 없다고 나와서 lag가 영원히 0이 아닌 것으로 계산되고, 메시지가
    // 하나라도 발행된 뒤로는 복구가 절대 진행되지 못하는 상태였다.
    @Value("${coupon.kafka.consumer.group-id:coupon-issue-consumer-group}")
    private String consumerGroupId;

    private final CouponIssueRepository couponIssueRepository;
    private final CouponPolicyRepository couponPolicyRepository;
    private final StringRedisTemplate redisTemplate;
    private final KafkaConsumerLagChecker lagChecker;
    private final RedisScript<Long> recoveryFinalizeScript;
    private final RedisScript<Long> renewLockScript;
    private final RedisScript<Long> releaseLockScript;

    public RedisRecoverResponse recover(Long policyId) {
        CouponPolicy policy = couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        String lockKey = lockKey(policyId);
        String lockToken = UUID.randomUUID().toString();
        if (!tryAcquireLock(lockKey, lockToken)) {
            throw new VerificationNotAllowedException(
                    "이미 이 정책에 대한 복구 작업이 진행 중입니다. 잠시 후 다시 시도해 주세요.");
        }

        ScheduledExecutorService heartbeat = startLockHeartbeat(lockKey, lockToken);
        try {
            long lag = lagChecker.getLag(TOPIC, consumerGroupId);
            if (lag != 0) {
                String reason = (lag < 0)
                        ? "Kafka consumer lag를 확인할 수 없어 복구를 진행하지 않습니다 (컨슈머/브로커 상태 확인 필요)."
                        : "Kafka consumer가 아직 " + lag + "건을 처리하지 못했습니다. "
                                + "DB가 최신 상태가 아니라 지금은 Redis를 재구성할 수 없습니다. 잠시 후 다시 시도해 주세요.";
                throw new VerificationNotAllowedException(reason);
            }

            return syncPolicyRedisState(policy, lag);
        } finally {
            heartbeat.shutdownNow();
            releaseLock(lockKey, lockToken);
        }
    }

    RedisRecoverResponse syncPolicyRedisState(CouponPolicy policy, long kafkaLag) {
        Long policyId = policy.getId();

        // count와 목록을 별도 쿼리로 나누면 그 사이 새 발급이 끼어들어 서로 다른 시점의
        // 스냅샷이 될 수 있다. 목록 하나만 읽고 count는 그 크기로 유도해서 항상 같은
        // 시점의 값이 되도록 한다.
        List<Long> issuedUserIds = couponIssueRepository.findUserIdsByCouponPolicyId(policyId);
        long issuedCount = issuedUserIds.size();
        if (issuedCount > policy.getTotalQuantity()) {
            log.warn("[RedisRecovery] policyId={} 발급 완료 건수({})가 총수량({})을 초과했습니다 — "
                            + "이미 초과 발급이 발생했을 수 있습니다. remainingStock은 0으로 재구성합니다.",
                    policyId, issuedCount, policy.getTotalQuantity());
        }
        int remainingStock = (int) Math.max(0, policy.getTotalQuantity() - issuedCount);

        String stockKey = RedisKeys.couponStock(policyId);
        String reservedKey = RedisKeys.couponReserved(policyId);
        String issuedKey = RedisKeys.couponIssued(policyId);
        String issuedStagingKey = RedisKeys.couponIssuedStaging(policyId);

        // 1단계: 실제 서비스 키(issuedKey)는 전혀 건드리지 않고 staging 키에만 채운다.
        // 여러 번의 SADD 왕복이 필요해 시간이 걸리지만, 실패하거나 중간에 죽어도
        // staging 키만 지저분해질 뿐 실제 서비스는 기존 상태 그대로 안전하다.
        redisTemplate.delete(issuedStagingKey);
        if (!issuedUserIds.isEmpty()) {
            String[] members = issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);
            for (int i = 0; i < members.length; i += REDIS_SADD_BATCH_SIZE) {
                String[] batch = Arrays.copyOfRange(members, i, Math.min(i + REDIS_SADD_BATCH_SIZE, members.length));
                redisTemplate.opsForSet().add(issuedStagingKey, batch);
            }
        }

        // staging을 채우는 동안(유저 수가 많으면 시간이 걸릴 수 있다) 새 발급이 계속
        // DB에 반영됐을 수 있다 — 그러면 방금 만든 staging은 이미 낡은 스냅샷이다.
        // finalize 직전에 목록을 다시 읽어 개수를 비교해서, 그 사이 뭔가 바뀌었으면
        // (증가만 가능 — CouponIssue는 append-only) 실제 키는 건드리지 않고 중단한다.
        // staging은 다음 재시도 때 다시 DEL 되므로 안전하게 버려진다.
        long recheckCount = couponIssueRepository.findUserIdsByCouponPolicyId(policyId).size();
        if (recheckCount != issuedCount) {
            throw new VerificationNotAllowedException(
                    "staging을 구성하는 동안 발급 완료 건수가 " + issuedCount + " → " + recheckCount
                            + "(으)로 바뀌었습니다. 지금 만든 staging은 낡은 스냅샷이라 반영하지 않습니다. "
                            + "잠시 후 다시 시도해 주세요.");
        }

        // 2단계: stock/reserved/issued 세 키를 한 Lua 스크립트로 원자적으로 교체한다.
        redisTemplate.execute(
                recoveryFinalizeScript,
                List.of(stockKey, reservedKey, issuedKey, issuedStagingKey),
                String.valueOf(remainingStock));

        log.info("[RedisRecovery] policyId={} 복구 완료 - issuedCount={}, remainingStock={}",
                policyId, issuedCount, remainingStock);

        return RedisRecoverResponse.success(policyId, policy.getTotalQuantity(), issuedCount, remainingStock, kafkaLag);
    }

    private String lockKey(Long policyId) {
        return "recover:lock:" + policyId;
    }

    private boolean tryAcquireLock(String lockKey, String lockToken) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    /** 락이 살아있는 동안 LOCK_RENEW_INTERVAL마다 소유권을 확인하고 TTL을 연장한다(watchdog). */
    private ScheduledExecutorService startLockHeartbeat(String lockKey, String lockToken) {
        // daemon 스레드로 만든다 — shutdownNow()의 인터럽트를 작업이 못 받는 드문 경우에도
        // 이 스레드 때문에 JVM 종료가 막히는 일이 없도록 한다.
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        executor.scheduleAtFixedRate(() -> renewLockSafely(lockKey, lockToken),
                LOCK_RENEW_INTERVAL.toMillis(), LOCK_RENEW_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        return executor;
    }

    private ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "redis-recovery-lock-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
    }

    private void renewLockSafely(String lockKey, String lockToken) {
        try {
            Long renewed = redisTemplate.execute(
                    renewLockScript, List.of(lockKey), lockToken, String.valueOf(LOCK_TTL.toMillis()));
            if (renewed == null || renewed == 0) {
                log.warn("[RedisRecovery] lockKey={} 갱신 실패 - 다른 프로세스가 락을 가져갔을 수 있습니다.", lockKey);
            }
        } catch (Exception e) {
            log.warn("[RedisRecovery] lockKey={} 갱신 중 예외", lockKey, e);
        }
    }

    /** 내가 잡은 락(토큰이 일치하는 경우)만 지운다 — TTL 만료로 남의 락을 잘못 지우는 사고 방지. */
    private void releaseLock(String lockKey, String lockToken) {
        redisTemplate.execute(releaseLockScript, List.of(lockKey), lockToken);
    }
}
