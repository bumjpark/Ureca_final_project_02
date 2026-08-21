package com.ureca.myureca.service;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaCouponEventProducer {
    private static final String TOPIC = "coupon-issued-events";
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
}