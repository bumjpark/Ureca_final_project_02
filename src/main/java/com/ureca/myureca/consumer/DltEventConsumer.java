package com.ureca.myureca.consumer;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code coupon-issued-events.DLT} 토픽 구독 Consumer.
 *
 * <p>오케스트레이션 전담: 배치 수신 → 순차 for-loop → 레코드 단위 위임. 실제 DB 적재 로직과
 * {@code @Transactional}은 {@link DltEventProcessor}에 있다 — {@link KafkaCouponEventConsumer}와
 * 동일하게 self-invoke 문제를 피하기 위해 별도 Bean으로 분리했다.
 *
 * <p>메인 이벤트 컨슈머({@link KafkaCouponEventConsumer})와 같은
 * {@code couponIssuedEventListenerContainerFactory}를 재사용한다 — 역직렬화 방식(Jackson 3.x
 * 커스텀 Deserializer + ErrorHandlingDeserializer)이 DLT 메시지 값(CouponIssuedEvent 또는
 * 역직렬화 실패 시 null)에도 그대로 맞기 때문이다. 이 리스너 자체가 실패하면(예: DB 장애)
 * 같은 DefaultErrorHandler가 재시도 후 {@link DltPublishingFailureRecoverer}로 다시 위임한다 —
 * DLT 적재 로직도 스스로의 실패에 대해 같은 안전망을 그대로 물려받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DltEventConsumer {

    private static final String DLT_TOPIC = "coupon-issued-events.DLT";

    private final DltEventProcessor processor;

    @KafkaListener(
            topics = DLT_TOPIC,
            groupId = "${coupon.kafka.dlt-consumer.group-id:coupon-dlt-log-consumer-group}",
            concurrency = "${coupon.kafka.dlt-consumer.concurrency:1}",
            containerFactory = "couponIssuedEventListenerContainerFactory"
    )
    public void consume(List<ConsumerRecord<String, CouponIssuedEvent>> records) {
        log.debug("[DltEventConsumer] 배치 수신 — topic={}, size={}", DLT_TOPIC, records.size());
        for (ConsumerRecord<String, CouponIssuedEvent> record : records) {
            processor.processSingle(record);
        }
    }
}
