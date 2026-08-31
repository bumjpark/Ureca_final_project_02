package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기열 유저를 1초마다 주기적으로 입장시키는 백그라운드 스케줄러.
 *
 * <p>다중 서버 인스턴스 환경에서 분산 락(Redis SETNX)을 획득한 단일 인스턴스만 실행하며,
 * 관리자가 동적으로 조정한 실시간 Limit(QueueLimitAdminService)을 매초 반영한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueAdmissionScheduler {

    private final CouponPolicyCacheService couponPolicyCacheService;
    private final QueueAdmissionService queueAdmissionService;
    private final QueueLimitAdminService queueLimitAdminService;
    private final StringRedisTemplate redisTemplate;

    /** 재고를 알 수 없음(키 미초기화/파싱 실패) — 이 값이면 자동 스케일링을 하지 않는다. */
    private static final long STOCK_UNKNOWN = -1L;

    /**
     * 매초(기본 1000ms) 실행되는 대기열 입장 배치.
     */
    @Scheduled(fixedDelayString = "${coupon.queue.admission-interval-ms:1000}")
    public void processQueueAdmission() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 현재 오픈 진행 중인 쿠폰 정책들 조회 (In-Memory 캐시로 매초 DB IOPS 0 유지)
        List<CouponPolicyCacheService.CachedPolicy> activePolicies = couponPolicyCacheService.getActivePolicies()
                .stream()
                .filter(policy -> isOpen(policy, now))
                .toList();

        for (CouponPolicyCacheService.CachedPolicy policy : activePolicies) {
            admitWithLock(policy.id());
        }
    }

    /** 오픈 시각은 지났고 마감 시각(없을 수 있음)은 아직 안 지난 정책인가. */
    private boolean isOpen(CouponPolicyCacheService.CachedPolicy policy, LocalDateTime now) {
        return !now.isBefore(policy.openAt())
                && (policy.closeAt() == null || !now.isAfter(policy.closeAt()));
    }

    /**
     * 2. 분산 락 획득 시도 (1초 TTL) - 다중 인스턴스 중복 실행 방어.
     * 락을 잡은 인스턴스만 이 정책의 입장 배치를 돌린다.
     */
    private void admitWithLock(Long policyId) {
        String lockKey = RedisKeys.lockAdmission(policyId);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", 1, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("다른 서버 인스턴스에서 이미 입장 배치를 실행 중입니다. policyId={}", policyId);
            return;
        }
        try {
            admitOnce(policyId);
        } catch (Exception e) {
            log.error("대기열 입장 스케줄러 실행 중 오류 발생. policyId={}", policyId, e);
        }
    }

    /** 락을 잡은 상태에서 정책 하나에 대해 실제로 수행하는 입장 처리 1회분. */
    private void admitOnce(Long policyId) {
        // 0) 입장 후 임계 시간(토큰 TTL + 여유)을 넘도록 /issue를 안 부른 이탈자를
        //    pending에서 걷어낸다 — 이걸 먼저 해야 바로 아래에서 계산하는 admission
        //    가용량(재고 - pending)이 이탈자 몫만큼 과소 계산되지 않는다.
        queueAdmissionService.reclaimStalePendingAdmissions(policyId);

        // 1) 재고 소진(0 이하)된 정책은 Redis 레벨에서 조기 스킵하여 불필요한 연산 방어.
        //    여기서 읽은 잔여 재고는 2)의 자동 스케일링 안전 상한 계산에도 그대로 쓴다
        //    (같은 틱에서 두 번 읽으면 서로 다른 시점의 값이 섞일 수 있다).
        Long stock = readRemainingStock(policyId);
        if (stock != null && stock <= 0) {
            log.debug("재고 소진 정책 입장 처리 스킵. policyId={}", policyId);
            return;
        }
        long remainingStock = (stock != null) ? stock : STOCK_UNKNOWN;

        // 2) 실시간 동적 Limit + 대기열 부하 기반 자동 스케일링 적용
        //    (잔여 재고 대비 안전 상한으로 과다 입장/FCFS 역전 위험을 조인다 — 이슈 #8)
        String queueKey = RedisKeys.couponQueue(policyId);
        Long queueSize = redisTemplate.opsForZSet().zCard(queueKey);
        long currentSize = (queueSize != null) ? queueSize : 0L;

        int effectiveLimit =
                queueLimitAdminService.calculateAutoScaledLimit(policyId, currentSize, remainingStock);
        queueAdmissionService.admitUsers(policyId, effectiveLimit);
    }

    /**
     * 잔여 재고 조회. 키가 없거나 숫자로 파싱되지 않으면 {@code null}(= 재고를 알 수 없음)을
     * 돌려주고, 그 경우 호출부는 스킵하지 않고 {@link #STOCK_UNKNOWN}으로 진행한다.
     */
    private Long readRemainingStock(Long policyId) {
        String stockStr = redisTemplate.opsForValue().get(RedisKeys.couponStock(policyId));
        if (stockStr == null) {
            return null;
        }
        try {
            return (long) Integer.parseInt(stockStr);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
