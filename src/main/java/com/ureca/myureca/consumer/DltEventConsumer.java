package com.ureca.myureca.consumer;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
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
 *
 * <p>이슈 #19: {@link KafkaCouponEventConsumer}가 #15에서 겪었던 것과 같은 poison-record 격리
 * 문제를 이 컨슈머도 물려받는다 — 레코드 하나가 예외를 던지면(DataIntegrityViolationException은
 * 별도 처리하지만, 그 외 예외는) {@link BatchListenerFailedException}으로 해당 레코드의 절대
 * 인덱스를 명시해서, Spring Kafka가 그 레코드로만 정확히 seek-back하게 한다. DLT 유입량은
 * 정상 상황에서 매우 적어 #15만큼 큰 폭발 반경은 아니지만, 재발행 폭주 등으로 유입이 급증하는
 * 상황을 대비해 원본 컨슈머와 동일한 방어를 갖춘다.
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
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, CouponIssuedEvent> record = records.get(i);
            try {
                processor.processSingle(record);
            } catch (DataIntegrityViolationException dive) {
                // 진짜 실패가 아니다(이슈 #11) — eventKey UNIQUE 위반으로 트랜잭션을 정상 롤백시키기
                // 위해 processor가 일부러 다시 던진 것뿐이다. 로그는 processor 안에서 이미 남겼으므로
                // 여기서는 조용히 다음 레코드로 넘어간다.
            } catch (Exception e) {
                // 진짜 실패(이슈 #19) — 이 레코드의 절대 인덱스로만 격리해서, 이미 처리된 앞선
                // 레코드나 아직 처리 안 된 뒤쪽 레코드까지 통째로 재시도/DLT 대상이 되지 않게 한다.
                log.warn("[DltEventConsumer] 레코드 처리 실패 — index={}만 격리해 재시도 대상으로 넘김. offset={}",
                        i, record.offset(), e);
                throw new BatchListenerFailedException(
                        "DLT 레코드 처리 실패 — offset=" + record.offset(), e, i);
            }
        }
    }
}
