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

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
