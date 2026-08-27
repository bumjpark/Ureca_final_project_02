package com.ureca.myureca.consumer;

import tools.jackson.databind.ObjectMapper;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 재시도 모두 실패 시 구조화된 로그를 기록하는 복구 구현체.
 *
 * <p>(UBM-37 이전) 이 클래스가 {@link com.ureca.myureca.config.KafkaConsumerConfig}에 주입되는
 * 기본 구현체였으나, 지금은 {@link DltPublishingFailureRecoverer}(@Primary)로 교체됐다.
 * 이 클래스는 DLT 발행 자체가 실패했을 때의 폴백으로 계속 쓰인다 — 로그 포맷 필드 구조를 유지할 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingFailureRecoverer implements ConsumerFailureRecoverer {

    private final ObjectMapper objectMapper;

    @Override
    public void recover(ConsumerRecord<?, ?> record, Exception ex) {
        FailureReason reason = FailureReason.from(ex);
        CouponIssuedEvent event = parseEvent(record);

        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("event", "COUPON_CONSUME_FAILED");
        logEntry.put("receiptId", event != null ? event.receiptId() : "UNKNOWN");
        logEntry.put("userId", event != null ? event.userId() : null);
        logEntry.put("couponPolicyId", event != null ? event.policyId() : null);
        logEntry.put("topic", record.topic());
        logEntry.put("partition", record.partition());
        logEntry.put("offset", record.offset());
        logEntry.put("attemptCount", 3);
        logEntry.put("failureReason", reason.name());
        logEntry.put("failureMessage", ex != null ? ex.getMessage() : "null");
        logEntry.put("timestamp", LocalDateTime.now().toString());

        try {
            log.error(objectMapper.writeValueAsString(logEntry));
        } catch (Exception jsonEx) {
            // 로그 직렬화 실패 시 fallback — 구조화 실패해도 반드시 기록
            log.error("COUPON_CONSUME_FAILED: receiptId={}, topic={}, partition={}, offset={}, reason={}, msg={}",
                    logEntry.get("receiptId"), record.topic(), record.partition(), record.offset(),
                    reason, ex != null ? ex.getMessage() : "null");
        }
    }

    /**
     * 레코드 값에서 CouponIssuedEvent를 역직렬화한다.
     * 파싱 실패 시 null을 반환하며, 이 경우 receiptId 등은 "UNKNOWN"으로 기록된다.
     */
    private CouponIssuedEvent parseEvent(ConsumerRecord<?, ?> record) {
        Object value = record.value();
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof CouponIssuedEvent event) {
                return event;
            }
            String json = value instanceof String s ? s : objectMapper.writeValueAsString(value);
            return objectMapper.readValue(json, CouponIssuedEvent.class);
        } catch (Exception e) {
            log.warn("LoggingFailureRecoverer: CouponIssuedEvent 파싱 실패 — receiptId UNKNOWN으로 기록. topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset(), e);
            return null;
        }
    }
}
