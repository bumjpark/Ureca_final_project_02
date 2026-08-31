package com.ureca.myureca.consumer;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 재시도(FixedBackOff) 모두 실패한 메시지를 DLT(Dead Letter Topic)로 실제 이적재하는 복구 구현체.
 *
 * <p>{@link LoggingFailureRecoverer}를 대체한다. {@link com.ureca.myureca.config.KafkaConsumerConfig}가
 * {@link ConsumerFailureRecoverer} 인터페이스만 알기 때문에, 이 클래스에 {@link Primary}를 붙여
 * 주입 대상만 바꿨을 뿐 Consumer 비즈니스 로직({@link KafkaCouponEventConsumer},
 * {@link CouponIssuedEventProcessor})은 전혀 건드리지 않는다.
 *
 * <p>DLT 토픽 이름은 원본 토픽 + ".DLT" 컨벤션을 따른다 (coupon-issued-events → coupon-issued-events.DLT).
 * 파티션은 강제하지 않고 원본 파티션 키(policyId_userId)만 그대로 유지한다 — DLT 토픽이 원본과
 * 파티션 수가 다를 수 있어 특정 파티션 번호를 강제하면 오히려 발행이 실패할 수 있기 때문이다.
 *
 * <p>실패 원인·원본 토픽/파티션/오프셋 등 진단 정보는 Kafka 헤더에 담아, DLT를 조회/재처리하는 쪽이
 * 메시지 값만 봐서는 알 수 없는 컨텍스트까지 함께 확인할 수 있게 한다.
 *
 * <p>DLT 발행 자체가 실패하면(브로커 장애 등) {@link LoggingFailureRecoverer}로 폴백해 최소한
 * 구조화된 로그는 남긴다 — DLT 발행 실패로 인한 완전 유실(로그도 DLT도 없음)을 막기 위함이다.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class DltPublishingFailureRecoverer implements ConsumerFailureRecoverer {

    private static final String DLT_SUFFIX = ".DLT";
    private static final long PUBLISH_TIMEOUT_SECONDS = 5L;

    public static final String HEADER_FAILURE_REASON = "X-Failure-Reason";
    public static final String HEADER_FAILURE_MESSAGE = "X-Failure-Message";
    public static final String HEADER_ORIGINAL_TOPIC = "X-Original-Topic";
    public static final String HEADER_ORIGINAL_PARTITION = "X-Original-Partition";
    public static final String HEADER_ORIGINAL_OFFSET = "X-Original-Offset";
    public static final String HEADER_FAILED_AT = "X-Failed-At";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    /** DLT 발행 자체가 실패했을 때의 최후 폴백 — 인터페이스가 아닌 구체 타입으로 직접 주입받아
     *  이 클래스의 {@code @Primary} 지정과 순환되지 않게 한다. */
    private final LoggingFailureRecoverer fallbackRecoverer;

    @Override
    public void recover(ConsumerRecord<?, ?> record, Exception ex) {
        FailureReason reason = FailureReason.from(ex);
        String dltTopic = record.topic() + DLT_SUFFIX;
        String key = record.key() != null ? String.valueOf(record.key()) : null;
        Object value = extractPublishableValue(record);

        ProducerRecord<String, Object> dltRecord = new ProducerRecord<>(dltTopic, key, value);
        addHeader(dltRecord, HEADER_FAILURE_REASON, reason.name());
        addHeader(dltRecord, HEADER_FAILURE_MESSAGE, ex != null ? String.valueOf(ex.getMessage()) : "null");
        addHeader(dltRecord, HEADER_ORIGINAL_TOPIC, record.topic());
        addHeader(dltRecord, HEADER_ORIGINAL_PARTITION, String.valueOf(record.partition()));
        addHeader(dltRecord, HEADER_ORIGINAL_OFFSET, String.valueOf(record.offset()));
        addHeader(dltRecord, HEADER_FAILED_AT, LocalDateTime.now().toString());

        try {
            kafkaTemplate.send(dltRecord).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.warn("[DLT] 메시지 이적재 완료 — dltTopic={}, originalTopic={}, partition={}, offset={}, reason={}",
                    dltTopic, record.topic(), record.partition(), record.offset(), reason);
        } catch (Exception publishEx) {
            log.error("[DLT] DLT 발행 자체가 실패 — 로그로 폴백. originalTopic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset(), publishEx);
            fallbackRecoverer.recover(record, ex);
        }
    }

    /**
     * record.value()가 이미 역직렬화된 {@link CouponIssuedEvent}면 그대로, 역직렬화 자체가 실패해
     * null이거나 다른 타입이면 재처리 시점에 복구할 데이터가 없다는 뜻이므로 null을 그대로 DLT에 싣는다
     * (헤더의 실패 정보는 그대로 남아 원인 파악은 가능하다).
     */
    private Object extractPublishableValue(ConsumerRecord<?, ?> record) {
        Object value = record.value();
        return (value instanceof CouponIssuedEvent) ? value : null;
    }

    private void addHeader(ProducerRecord<String, Object> record, String key, String value) {
        record.headers().add(key, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
