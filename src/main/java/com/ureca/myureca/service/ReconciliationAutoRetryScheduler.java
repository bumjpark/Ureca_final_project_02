package com.ureca.myureca.service;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final List<ReconciliationStatus> RETRYABLE_STATUSES =
            List.of(ReconciliationStatus.PENDING, ReconciliationStatus.FAILED);

    /** ISSUE_REPROCESS(정합성 검증 배치가 만드는 상태 불일치 수동 재처리)와 REDIS_RECOVER(완전
     *  유실 복구 이력)는 "자동으로 다시 쏴도 안전한 이벤트 재발행"이 아니라 사람 판단이 필요한
     *  성격이라 자동 재시도 대상에서 제외한다. */
    private static final List<ReconciliationType> AUTO_RETRY_TYPES =
            List.of(ReconciliationType.EVENT_REPUBLISH, ReconciliationType.DLT_REPROCESS);

    private final ReconciliationLogRepository reconciliationLogRepository;
    private final ReconciliationRetryTrigger reconciliationRetryTrigger;

    @Scheduled(fixedDelayString = "${coupon.reconciliation.auto-retry-interval-ms:60000}")
    public void retryPendingAndFailed() {
        for (ReconciliationType type : AUTO_RETRY_TYPES) {
            retryType(type);
        }
    }

    private void retryType(ReconciliationType type) {
        List<ReconciliationLog> targets = reconciliationLogRepository
                .findByTypeAndStatusIn(type, RETRYABLE_STATUSES)
                .stream()
                .filter(log -> log.getRetryCount() < MAX_AUTO_RETRY_COUNT)
                .toList();

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
