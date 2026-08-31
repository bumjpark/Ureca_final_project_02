package com.ureca.myureca.service;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code reconciliation_log}에 쌓인 EVENT_REPUBLISH(이슈 #2)/DLT_REPROCESS(이슈 #6) 건을
 * 사람 개입 없이 주기적으로 자동 재처리한다.
 *
 * <p>이 스케줄러가 없으면 두 재처리 타입 모두 {@code POST /api/admin/reconciliation/retry}를
 * 누군가 수동으로 호출해야만 재발행되는데, 실무에서 이 큐를 계속 들여다볼 사람이 없으면 PENDING
 * 상태로 무한정 쌓이기만 하고, 그 이벤트가 참조하던 Redis {@code reserved} 항목도 영원히
 * 청소되지 않는다(이슈 #3의 근본 원인) — 유저는 재고만 차감된 채 영원히 발급을 못 받고, 같은
 * 정책에 재진입도 막힌다(409).
 *
 * <p>재처리 동작 자체({@code KafkaCouponEventProducer.publishCouponIssuedEventForRetry} →
 * {@code KafkaCouponEventConsumer} → 인박스 체크(1차) + DB UNIQUE 제약(2차))는 이미 멱등하게
 * 설계돼 있으므로, 같은 건을 여러 번 재발행해도 중복 발급되지 않는다 — 이 스케줄러는 그 위에
 * "누가 눌러줄 때까지 기다리지 않고 스스로 재시도한다"만 얹는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationAutoRetryScheduler {

    /** 이 횟수를 넘으면 자동 재처리를 멈춘다 — payload 자체가 영구적으로 깨진 건(예: 역직렬화
     *  실패) 무한 재시도로 로그만 채우는 것을 방지. 수동 재처리는 이 한도와 무관하게 항상 가능하다. */
    private static final int MAX_AUTO_RETRY_COUNT = 5;

    /** 한 틱(instance)당 최대 처리 건수(이슈 #21) — 장애가 길어져 PENDING이 많이 쌓여도, 막
     *  복구된 브로커/컨슈머를 한 번에 밀어버리지 않도록 상한을 둔다. 남은 건 다음 틱에 이어서 처리. */
    private static final int MAX_BATCH_SIZE_PER_TICK = 500;

    /** 분산 락 TTL. 스케줄 주기(기본 60초)보다 충분히 짧게 둬서, 한 틱 처리가 늦어져도 락이
     *  자연히 풀려 다음 인스턴스가 잡을 수 있게 한다(QueueAdmissionScheduler의 1초 TTL과 같은 원칙,
     *  다만 이쪽은 배치 처리라 여유를 더 둠). */
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private static final List<ReconciliationStatus> RETRYABLE_STATUSES =
            List.of(ReconciliationStatus.PENDING, ReconciliationStatus.FAILED);

    /** ISSUE_REPROCESS(정합성 검증 배치가 만드는 상태 불일치 수동 재처리)와 REDIS_RECOVER(완전
     *  유실 복구 이력)는 "자동으로 다시 쏴도 안전한 이벤트 재발행"이 아니라 사람 판단이 필요한
     *  성격이라 자동 재시도 대상에서 제외한다. */
    private static final List<ReconciliationType> AUTO_RETRY_TYPES =
            List.of(ReconciliationType.EVENT_REPUBLISH, ReconciliationType.DLT_REPROCESS);

    private final ReconciliationLogRepository reconciliationLogRepository;
    private final ReconciliationRetryTrigger reconciliationRetryTrigger;
    private final StringRedisTemplate redisTemplate;

    @Scheduled(fixedDelayString = "${coupon.reconciliation.auto-retry-interval-ms:60000}")
    public void retryPendingAndFailed() {
        for (ReconciliationType type : AUTO_RETRY_TYPES) {
            // type별로 분산 락을 따로 걸어 EVENT_REPUBLISH/DLT_REPROCESS가 서로 다른 인스턴스에서
            // 동시에 처리될 수 있게 한다 — 이슈 #21: 락이 없으면 N개 인스턴스가 매 틱마다 같은
            // 행을 동시에 재발행해 retryCount가 틱당 N배로 소진되고 MAX_AUTO_RETRY_COUNT를
            // 1라운드 만에 다 써버린다.
            String lockKey = RedisKeys.lockReconciliationRetry(type.name());
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                retryType(type);
            } else {
                log.debug("[ReconciliationAutoRetry] 다른 인스턴스가 이미 처리 중입니다. type={}", type);
            }
        }
    }

    /**
     * {@code retryCount} 한도를 DB 조회 조건으로 넘긴다 — 조회 후 자바에서 거르면 안 된다.
     * 재시도를 다 소진한 행은 PENDING/FAILED 상태 그대로 남아 {@code created_at ASC} 선두를
     * 영구히 차지하므로, 그런 행이 {@link #MAX_BATCH_SIZE_PER_TICK}만큼 쌓이면 페이지가 통째로
     * 소진된 행으로만 채워져 뒤에 들어온 정상 대상이 영원히 조회되지 않는다(= 자동 재처리가
     * 조용히 완전 정지). 자세한 배경은 리포지토리 메서드 주석 참고.
     */
    private void retryType(ReconciliationType type) {
        List<ReconciliationLog> targets = reconciliationLogRepository
                .findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                        type, RETRYABLE_STATUSES, MAX_AUTO_RETRY_COUNT,
                        PageRequest.of(0, MAX_BATCH_SIZE_PER_TICK));

        for (ReconciliationLog target : targets) {
            retryOne(target);
        }
    }

    private void retryOne(ReconciliationLog target) {
        Long logId = target.getId();
        try {
            reconciliationRetryTrigger.dispatch(logId);
            log.info("[ReconciliationAutoRetry] 자동 재처리 접수 - type={}, logId={}, eventKey={}, retryCount={}",
                    target.getType(), logId, target.getEventKey(), target.getRetryCount());
        } catch (Exception e) {
            // ReconciliationAlreadySucceededException(그 사이 수동 재처리 등으로 이미 성공) 같은
            // 경합은 흔하고 무해하다 — 다음 조회 때는 SUCCESS라 자연히 대상에서 빠진다.
            log.debug("[ReconciliationAutoRetry] logId={} 자동 재처리 스킵/실패: {}", logId, e.getMessage());
        }
    }
}
