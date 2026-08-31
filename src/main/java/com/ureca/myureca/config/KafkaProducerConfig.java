package com.ureca.myureca.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);

        // 대용량 트래픽 처리를 위한 배치 및 전송 신뢰성(안전장치) 설정
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024); // 32KB 배치 크기
        config.put(ProducerConfig.LINGER_MS_CONFIG, 10);          // 최대 10ms 대기 후 배치 전송
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy"); // 빠른 압축으로 네트워크 부하 감소
        config.put(ProducerConfig.ACKS_CONFIG, "1");             // 브로커 수신 확인으로 유실 방지

        // Kafka 브로커 장애 시 요청 스레드가 묶이는 것을 막기 위한 타임아웃(실측 확인, 2026-08-28).
        // 기본값(MAX_BLOCK_MS=60000)을 그대로 두면 KafkaProducer.send() 자체가 브로커 장애 중
        // 최대 60초 동안 호출 스레드를 블로킹한다 — 부하테스트 중 Kafka를 강제 종료해보니 join/issue
        // 요청의 중간값 응답시간이 그대로 59.99초가 됐고, Tomcat 스레드가 고갈되며 요청 실패율이
        // 52%까지 치솟았다. 여기서 빨리 실패시켜야 EVENT_REPUBLISH 재처리 경로(이미 있는 안전망)로
        // 넘길 수 있다 — 지금 값(60s)은 그 안전망에 도달하기도 전에 스레드부터 고갈시킨다.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);       // 버퍼/메타데이터 대기 상한 3초
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000); // 브로커 응답 대기 상한 5초
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000); // 재시도 포함 전체 전송 상한 10초
        // (delivery.timeout.ms >= linger.ms + request.timeout.ms 제약을 만족해야 하므로 10s로 여유를 둠)

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
