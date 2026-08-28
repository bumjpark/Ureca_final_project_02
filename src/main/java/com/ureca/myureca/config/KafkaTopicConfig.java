package com.ureca.myureca.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 토픽 파티션 수를 코드로 선언해 영속화한다.
 *
 * <p><b>배경</b>: {@code coupon-issued-events} 토픽의 파티션을 1 → 3으로 늘린 변경
 * (Docs/load-test/2026-08-28-kafka-partition-bottleneck-investigation.md 참고 — 다른 LLM이
 * 제기한 "파티션 1개가 처리량 병목"이라는 진단을 실측으로 검증하는 과정에서 수행)이 지금까지는
 * {@code kafka-topics.sh --alter}로 브로커에 직접 실행한 **런타임 상태**로만 존재했다. 코드/설정
 * 어디에도 선언돼 있지 않아서, {@code docker compose down -v}로 볼륨을 초기화하거나 이
 * 저장소를 새로 클론해서 처음 띄우면 Kafka 기본값(파티션 1개)으로 조용히 되돌아가는 문제가
 * 있었다.
 *
 * <p><b>⚠️ {@code KafkaAdmin} 빈을 직접 등록해야 하는 이유(실측으로 확인, 2026-08-28)</b>:
 * {@code NewTopic} 빈만 선언하면 될 거라 생각했는데, 이 프로젝트는 {@code KafkaProducerConfig}/
 * {@code KafkaConsumerConfig}처럼 Kafka를 Spring Boot 자동설정에 기대지 않고 전부 수동으로
 * 구성하는 관례를 따르고 있어서 {@code KafkaAdmin} 빈이 애초에 존재하지 않았다
 * ({@link HealthCheckConfig}의 기존 주석 참고). 실제로 topic을 삭제하고 이 프로젝트를 처음
 * 띄우는 상황을 재현해봤더니, {@code NewTopic} 빈만 있는 상태로는 브로커의 자동 토픽 생성
 * (기본 파티션 1개)이 먼저 일어나버려 아무 효과가 없었다 — {@code KafkaAdmin}이 실제로
 * 컨텍스트에 있어야 기동 시점에 {@link NewTopic} 빈들을 스캔해서 토픽을 만들거나(신규 환경)
 * 파티션 부족분을 {@code createPartitions()}로 보정한다(기존 환경). 그래서 이 클래스에서
 * {@code KafkaAdmin}도 같은 수동 구성 관례로 함께 등록한다.
 *
 * <p>{@code coupon-issued-events.DLT}, {@code queue-join-events} 등 다른 토픽은 이번 조사
 * 범위 밖이라 건드리지 않았다 — 기존처럼 최초 produce 시 파티션 1개로 자동 생성되는 동작 그대로.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new KafkaAdmin(config);
    }

    @Bean
    public NewTopic couponIssuedEventsTopic() {
        return TopicBuilder.name("coupon-issued-events")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
