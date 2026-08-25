package com.ureca.myureca.service;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaCouponEventProducer {
    private static final String TOPIC = "coupon-issued-events";
    private static final String QUEUE_JOIN_TOPIC = "queue-join-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishCouponIssuedEvent(CouponIssuedEvent event) {
        // 동일 정책+유저는 같은 파티션으로 전송되어 순서 보장
        String partitionKey = event.policyId() + "_" + event.userId();
        kafkaTemplate.send(TOPIC, partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 쿠폰 발행 이벤트 전송 실패 - policyId: {}, userId: {}, receiptId: {}",
                                event.policyId(), event.userId(), event.receiptId(), ex);
                    } else {
                        log.info("Kafka 쿠폰 발행 이벤트 전송 성공 - policyId: {}, userId: {}, receiptId: {}, offset: {}",
                                event.policyId(), event.userId(), event.receiptId(), result.getRecordMetadata().offset());
                    }
                });
    }

    /**
     * 정합성 복구(수동 재처리)용 발행
     */
    public CompletableFuture<SendResult<String, Object>> publishCouponIssuedEventForRetry(CouponIssuedEvent event) {
        String partitionKey = event.policyId() + "_" + event.userId();
        return kafkaTemplate.send(TOPIC, partitionKey, event);
    public void publishQueueJoinEvent(com.ureca.myureca.dto.event.QueueJoinEvent event) {
        // 동일 정책+유저는 같은 파티션으로 전송되어 선착순 순서 영속화
        String partitionKey = event.policyId() + "_" + event.userId();
        kafkaTemplate.send(QUEUE_JOIN_TOPIC, partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Kafka 대기열 진입 이벤트 전송 실패 (비차단 fallback) - policyId: {}, userId: {}, status: {}",
                                event.policyId(), event.userId(), event.status(), ex);
                    } else {
                        log.debug("Kafka 대기열 진입 이벤트 전송 성공 - policyId: {}, userId: {}, offset: {}",
                                event.policyId(), event.userId(), result.getRecordMetadata().offset());
                    }
                });
    }
}