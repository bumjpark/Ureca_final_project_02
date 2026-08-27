package com.ureca.myureca.consumer;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;


/**
 * Jackson 3.x (tools.jackson) 기반의 CouponIssuedEvent 역직렬화기.
 *
 * <p>Spring Kafka의 JsonDeserializer는 Jackson 2.x(com.fasterxml.jackson)에 의존하고 있어,
 * Spring Boot 4.x(Jackson 3.x) 환경에서 TypeReference 누락 컴파일 오류가 발생한다.
 * 이를 회피하고 Jackson 3.x로 안전하게 역직렬화하기 위해 직접 Custom Deserializer를 구현하여 사용한다.
 *
 * <p>Spring Boot가 자동으로 구성해둔 ObjectMapper 빈을 주입받아 사용하므로,
 * LocalDateTime 역직렬화를 위한 JavaTimeModule 등 필요한 모듈이 자동으로 등록되어 제공된다.
 */
public class CouponIssuedEventDeserializer implements Deserializer<CouponIssuedEvent> {

    private final ObjectMapper objectMapper;

    public CouponIssuedEventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public CouponIssuedEvent deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(data, CouponIssuedEvent.class);
        } catch (Exception e) {
            // Spring Kafka ErrorHandlingDeserializer가 잡을 수 있도록
            // SerializationException 또는 RuntimeException을 던진다.
            throw new RuntimeException("Jackson 3.x deserialization failed for topic: " + topic, e);
        }
    }
}
