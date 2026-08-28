package com.ureca.myureca.consumer;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * coupon-issued-events 토픽 구독 Consumer.
 *
 * <p>오케스트레이션 전담: 배치 수신 → 청크 분할 → 청크 단위 위임, 실패 시 건별 폴백.
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

    @Value("${coupon.kafka.consumer.chunk-size:100}")
    private int chunkSize;

    /**
     * 배치 수신 → 청크 분할 → 청크 단위 처리(정상 경로) → 실패 시 그 청크만 건별 폴백.
     *
     * <p>배치를 통째로 하나의 트랜잭션으로 묶지 않는 이유는 이전과 동일(순서 보존,
     * poison-pill 하나로 전체가 물귀신처럼 실패하는 범위 최소화)이지만, 이벤트 1건마다
     * 트랜잭션을 여는 것도 커밋(디스크 fsync 포함) 횟수가 이벤트 수만큼 늘어나 비효율적이다.
     * 그 중간 지점으로 {@code chunkSize}(기본 100)건씩 묶어 처리한다.
     *
     * <p>청크 내부/청크 간 순서는 원본 배치 순서를 그대로 유지한다({@link List#subList}) —
     * 같은 파티션 키(policyId_userId)로 보장된 파티션 내 순서를 애플리케이션 레벨에서도
     * 지키기 위함이다.
     *
     * <p>{@link CouponIssuedEventProcessor#processChunk}가 예외를 던지면(청크 안에 문제
     * 있는 이벤트가 있어 트랜잭션 전체가 롤백된 경우) 그 청크에 한해
     * {@link CouponIssuedEventProcessor#processSingle}로 건별 재처리한다 — 청크 안의
     * 정상 이벤트를 구제하기 위함이다. 그마저도 실패하면(DB 커넥션 오류 등) 예외가
     * 그대로 전파되어 DefaultErrorHandler가 FixedBackOff로 재시도한다.
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
        log.debug("[KafkaConsumer] 배치 수신 — topic={}, size={}, chunkSize={}", TOPIC, events.size(), chunkSize);
        for (int start = 0; start < events.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, events.size());
            List<CouponIssuedEvent> chunk = events.subList(start, end);
            try {
                processor.processChunk(chunk);
            } catch (Exception e) {
                log.warn("[KafkaConsumer] 청크 처리 실패 — 건별 재처리로 폴백. size={}", chunk.size(), e);
                for (CouponIssuedEvent event : chunk) {
                    processor.processSingle(event);
                }
            }
        }
    }
}
