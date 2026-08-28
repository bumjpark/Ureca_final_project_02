package com.ureca.myureca.consumer;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * coupon-issued-events 토픽 구독 Consumer.
 *
 * <p>오케스트레이션 전담: 배치 수신 → 순차 for-loop → 이벤트 단위 위임.
 * 트랜잭션은 없다. 실제 저장 로직과 @Transactional은 {@link CouponIssuedEventProcessor}에 있다.
 *
 * <p>self-invoke 방지를 위해 Processor를 별도 Bean으로 분리한다.
 * 같은 클래스 내에 @KafkaListener와 @Transactional 메서드가 함께 있으면
 * Spring AOP 프록시가 self-invoke를 인터셉트하지 못해 트랜잭션이 적용되지 않는다.
 *
 * <p>concurrency 설정 주의:
 * concurrency 값은 파티션 수를 초과하면 안 된다. 초과 시 일부 스레드가 파티션을 배정받지 못해
 * 유휴 상태가 되고, 파티션당 단일 스레드 처리 보장이 깨진다.
 * 실제 파티션 수는 {@code kafka-topics.sh --describe --topic coupon-issued-events}로 확인할 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaCouponEventConsumer {

    private static final String TOPIC = "coupon-issued-events";

    private final CouponIssuedEventProcessor processor;

    /**
     * 배치 수신 → 순차 처리.
     *
     * <p>배치 내부에서 이벤트를 순서대로 처리하는 것은 파티션 키(policyId_userId)로 보장된
     * 파티션 내 순서를 애플리케이션 레벨에서도 유지하기 위함이다.
     * saveAll() 방식을 쓰면 순서가 흐트러질 수 있으므로 순차 for-loop를 사용한다.
     *
     * <p>배치 내 특정 이벤트가 실패하면 {@link CouponIssuedEventProcessor}에서 예외가 throw되고
     * DefaultErrorHandler가 수신하여 FixedBackOff로 재시도한다.
     * 이미 커밋된 앞 이벤트들은 롤백되지 않으며, 인박스 체크(existsByRequestId)로 재시도 시 스킵된다.
     *
     * @param events Kafka로 수신한 CouponIssuedEvent 배치 (max.poll.records 크기)
     */
    @KafkaListener(
            topics = TOPIC,
            groupId = "${coupon.kafka.consumer.group-id:coupon-issue-consumer-group}",
            concurrency = "${coupon.kafka.consumer.concurrency:3}",
            containerFactory = "couponIssuedEventListenerContainerFactory"
    )
    public void consume(List<CouponIssuedEvent> events) {
        processor.processBatch(events);
    }
}
