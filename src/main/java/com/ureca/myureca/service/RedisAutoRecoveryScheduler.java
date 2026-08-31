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
 * 오픈 중인 정책의 Redis 상태를 세 가지 각도에서 자동으로 살펴본다.
 * <ul>
 *   <li>{@link #recoverMissingStock}: {@code stock} 키가 아예 없는(Redis 유실) 경우를
 *       찾아 {@link RedisRecoveryService#recover}로 전체 재구성한다.</li>
 *   <li>{@link #reconcileReservedDrift}: {@code stock} 키는 멀쩡하지만 {@code reserved}
 *       ZSET에 DB엔 이미 커밋된 항목이 남아있는("부분 드리프트") 경우를 찾아
 *       {@link RedisRecoveryService#reconcileReservedDrift}로 가볍게 정리한다. 실측(2026-08-30,
 *       부하테스트 중 Redis 강제 종료)에서 확인된 것처럼, stock 키만 보는 첫 번째 검사로는
 *       이 케이스를 전혀 감지하지 못한다 — stock은 이미 정확했기 때문이다.</li>
 *   <li>{@link #detectStaleReservedDrift}: {@code reserved}에 DB에도 없이 임계 시간을 넘도록
 *       남은(진짜 재고 누수, Check D) 경우를 찾아 {@code reconciliation_log}(ISSUE_REPROCESS)에
 *       등록만 한다. 이 검사는 원래 {@code POST /api/admin/verification/run}을 사람이 눌러야만
 *       실행됐다 — 게다가 그 엔드포인트는 재고가 남은 정책이 하나라도 있으면 기본적으로 거부돼서,
 *       판매가 다 끝나기 전까진 아무리 재고 누수가 쌓여도 아무도 모를 수 있었다. Check D 자체는
 *       판매 진행 여부와 무관한 검사라 이 스케줄러로 떼어냈다. 발견해도 자동 재발급은 하지
 *       않는다(이중 발급 위험 — {@link VerificationAsyncTrigger#detectAndRegisterStaleReserved}
 *       주석 참고) — 등록까지만 자동화하고, 재처리 실행은 여전히 사람이 판단한다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAutoRecoveryScheduler {

    private final CouponPolicyRepository couponPolicyRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisRecoveryService redisRecoveryService;
    private final VerificationAsyncTrigger verificationAsyncTrigger;
    private final HealthCheckService healthCheckService;

    @Scheduled(fixedDelayString = "${coupon.redis-recovery.auto-interval-ms:5000}")
    public void recoverMissingStock() {
        if (!isRedisUp()) {
            log.debug("[RedisAutoRecovery] Redis가 아직 DOWN 상태라 이번 틱은 건너뜁니다.");
            return;
        }

        for (CouponPolicy policy : activePolicies()) {
            recoverIfStockMissing(policy.getId());
        }
    }

    /**
     * stock 키 존재 검사와 별개 주기로 도는 "부분 드리프트" 정리. 기본 간격을 stock 복구보다
     * 넉넉하게 잡은 이유: reserved 드리프트는 정상 운영 중엔 거의 항상 0건이라(스캔이 곧
     * ZCARD 수준으로 저렴하다) 자주 돌려도 부담이 적지만, 그래도 매 5초마다 활성 정책 전체를
     * 훑을 필요는 없어 기본값을 조금 더 여유 있게 뒀다. 필요하면 운영 환경에서 좁혀도 된다.
     */
    @Scheduled(fixedDelayString = "${coupon.redis-recovery.reserved-drift-interval-ms:10000}")
    public void reconcileReservedDrift() {
        if (!isRedisUp()) {
            log.debug("[RedisAutoRecovery] Redis가 아직 DOWN 상태라 reserved 드리프트 정리를 건너뜁니다.");
            return;
        }

        for (CouponPolicy policy : activePolicies()) {
            reconcileReservedDriftForPolicy(policy.getId());
        }
    }

    /**
     * Check D(미아 예약)만 독립적으로 주기 실행한다. 임계 시간(기본
     * {@code app.verification.stale-reserved-threshold}=5분)보다 이 주기를 충분히 짧게 둬야
     * "넘긴 직후"에 가깝게 발견한다 — 주기가 임계 시간에 육박하면 발견이 그만큼 늦어진다.
     */
    @Scheduled(fixedDelayString = "${coupon.redis-recovery.stale-reserved-interval-ms:60000}")
    public void detectStaleReservedDrift() {
        if (!isRedisUp()) {
            log.debug("[RedisAutoRecovery] Redis가 아직 DOWN 상태라 미아 예약 탐지를 건너뜁니다.");
            return;
        }

        for (CouponPolicy policy : activePolicies()) {
            detectStaleReservedDriftForPolicy(policy.getId());
        }
    }

    private List<CouponPolicy> activePolicies() {
        LocalDateTime now = LocalDateTime.now();
        return couponPolicyRepository.findByDeletedAtIsNull(Pageable.unpaged())
                .getContent()
                .stream()
                .filter(policy -> !now.isBefore(policy.getOpenAt())
                        && (policy.getCloseAt() == null || !now.isAfter(policy.getCloseAt())))
                .toList();
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

    private void reconcileReservedDriftForPolicy(Long policyId) {
        try {
            int fixed = redisRecoveryService.reconcileReservedDrift(policyId);
            if (fixed > 0) {
                log.info("[RedisAutoRecovery] policyId={} reserved 드리프트 {}건 자동 정리(reserved→issued)",
                        policyId, fixed);
            }
        } catch (Exception e) {
            // stock 재구성(recoverIfStockMissing)과 달리 이 정리는 재고/발급 개수를 전혀
            // 건드리지 않으므로 여기서 실패해도 안전 속성엔 영향이 없다 — 다음 틱에 다시 시도된다.
            log.error("[RedisAutoRecovery] policyId={} reserved 드리프트 정리 중 예상 못한 오류", policyId, e);
        }
    }

    private void detectStaleReservedDriftForPolicy(Long policyId) {
        try {
            int found = verificationAsyncTrigger.detectAndRegisterStaleReserved(policyId);
            if (found > 0) {
                log.info("[RedisAutoRecovery] policyId={} 미아 예약 {}건 발견 -> reconciliation_log 등록(자동 재발급 없음)",
                        policyId, found);
            }
        } catch (Exception e) {
            // 발견/등록만 하는 검사라 실패해도 재고·발급 정합성엔 영향이 없다 — 다음 틱에 재시도.
            log.error("[RedisAutoRecovery] policyId={} 미아 예약 탐지 중 예상 못한 오류", policyId, e);
        }
    }
}
