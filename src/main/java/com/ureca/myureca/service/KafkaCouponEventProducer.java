package com.ureca.myureca.service;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaCouponEventProducer {
    private static final String TOPIC = "coupon-issued-events";
    private static final String QUEUE_JOIN_TOPIC = "queue-join-events";
    private static final int FAIL_REASON_MAX_LENGTH = 255;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ReconciliationLogRepository reconciliationLogRepository;
    private final ObjectMapper objectMapper;

    public void publishCouponIssuedEvent(CouponIssuedEvent event) {
        // 동일 정책+유저는 같은 파티션으로 전송되어 순서 보장
        String partitionKey = event.policyId() + "_" + event.userId();
        kafkaTemplate.send(TOPIC, partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 쿠폰 발행 이벤트 전송 실패 - policyId: {}, userId: {}, receiptId: {}",
                                event.policyId(), event.userId(), event.receiptId(), ex);
                        recordPublishFailure(event, ex);
                    } else {
                        log.info("Kafka 쿠폰 발행 이벤트 전송 성공 - policyId: {}, userId: {}, receiptId: {}, offset: {}",
                                event.policyId(), event.userId(), event.receiptId(), result.getRecordMetadata().offset());
                    }
                });
    }

    /**
     * Kafka 발행 자체가 실패한 이벤트를 {@code reconciliation_log}에 {@link ReconciliationType#EVENT_REPUBLISH}
     * 행으로 적재한다(이슈 #2).
     *
     * <p>이 호출 이전에 이미 Redis {@code stock}이 DECR되고 {@code reserved}에 유저가 등록됐으며
     * 유저는 202 ACCEPTED를 받은 뒤다 — 여기서 발행 실패를 기록해두지 않으면 이 이벤트는 어디에도
     * 흔적 없이 증발하고, 그 유저는 재고만 차감된 채 영원히 아무것도 못 받는다(이슈 #3과 직결 —
     * {@code reserved}가 이 이벤트를 참조하는 유일한 흔적이 되는데, 이 로그가 없으면 나중에 그
     * 항목을 봐도 "왜 여기 멈춰있는지" 재구성할 방법이 없다).
     *
     * <p>{@code POST /api/admin/reconciliation/retry}(기존 EVENT_REPUBLISH 재처리 경로)가 이
     * payload를 그대로 재발행하므로, 여기서는 저장만 하면 재처리 인프라는 이미 갖춰져 있다.
     * 인박스 방어(eventKey UNIQUE)까지 {@link com.ureca.myureca.consumer.DltEventProcessor}와
     * 동일 패턴을 따른다.
     */
    private void recordPublishFailure(CouponIssuedEvent event, Throwable ex) {
        try {
            if (reconciliationLogRepository.existsByEventKey(event.receiptId())) {
                return;
            }
            String payload = objectMapper.writeValueAsString(event);
            ReconciliationLog reconciliationLog = new ReconciliationLog(
                    ReconciliationType.EVENT_REPUBLISH,
                    event.receiptId(),
                    null,   // coupon_issue_id: 아직 발급 자체가 안 됐으므로 연결할 대상이 없다.
                    TOPIC,
                    payload,
                    null    // requestedBy: 사람이 아니라 이 프로듀서가 자동으로 적재한 것이므로 비워둔다.
            );
            reconciliationLog.recordOriginalFailure(summarize(ex));
            reconciliationLogRepository.save(reconciliationLog);
            log.warn("[KafkaProducer] 발행 실패건 reconciliation_log(EVENT_REPUBLISH) 적재 완료 - receiptId={}",
                    event.receiptId());
        } catch (Exception saveEx) {
            // 여기서마저 실패하면 이 이벤트는 정말로 아무 흔적 없이 사라진다 — CRITICAL로 남겨서
            // 최소한 로그 검색으로는 추적 가능하게 한다.
            log.error("[KafkaProducer] CRITICAL: 발행 실패건을 reconciliation_log에도 적재하지 못함 "
                            + "(완전 유실 위험, 흔적이 이 로그뿐임) - policyId={}, userId={}, receiptId={}",
                    event.policyId(), event.userId(), event.receiptId(), saveEx);
        }
    }

    private String summarize(Throwable ex) {
        String message = ex != null ? String.valueOf(ex.getMessage()) : "unknown";
        return message.length() > FAIL_REASON_MAX_LENGTH ? message.substring(0, FAIL_REASON_MAX_LENGTH) : message;
    }

    /**
     * 정합성 복구(수동 재처리)용 발행
     */
    public CompletableFuture<SendResult<String, Object>> publishCouponIssuedEventForRetry(CouponIssuedEvent event) {
        String partitionKey = event.policyId() + "_" + event.userId();
        return kafkaTemplate.send(TOPIC, partitionKey, event);
    }

    public void publishQueueJoinEvent(com.ureca.myureca.dto.event.QueueJoinEvent event) {
        // 동일 정책의 이벤트는 같은 파티션으로 전송되어 선착순(seq) 순서 보장
        String partitionKey = String.valueOf(event.policyId());
        kafkaTemplate.send(QUEUE_JOIN_TOPIC, partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Kafka 대기열 진입 이벤트 전송 실패 (비차단 fallback) - policyId: {}, userId: {}, status: {}, seq: {}",
                                event.policyId(), event.userId(), event.status(), event.seq(), ex);
                    } else {
                        log.debug("Kafka 대기열 진입 이벤트 전송 성공 - policyId: {}, userId: {}, seq: {}, offset: {}",
                                event.policyId(), event.userId(), event.seq(), result.getRecordMetadata().offset());
                    }
                });
    }
}
