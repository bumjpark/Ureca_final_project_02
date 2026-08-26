package com.ureca.myureca.support;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 특정 Kafka consumer group이 특정 토픽을 얼마나 못 따라잡았는지(lag)를 구한다.
 *
 * <p>Redis 재구성(E)의 안전장치로 쓰인다. 상시 Consumer가 아직 DB 반영을 다 못 끝낸
 * 상태(lag != 0)에서 "DB → Redis 동기화"를 하면, 최근 발급 건이 통째로 Redis에서
 * 누락된 채로 재구성될 수 있다. 그래서 lag가 정확히 0일 때만 진행해야 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaConsumerLagChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** HealthCheckConfig가 등록해 둔 싱글턴 AdminClient를 재사용한다 (새로 만들지 않음). */
    private final AdminClient adminClient;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * @return 0 = 완전히 따라잡음(진행 가능), 양수 = 아직 처리 못한 메시지 수,
     *         -1 = 조회 자체에 실패(브로커 문제 등) — 호출부는 이 값도 "진행 불가"로 취급해야 한다.
     */
    public long getLag(String topic, String consumerGroupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(consumerGroupId)
                    .partitionsToOffsetAndMetadata()
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            Map<String, Object> config = new HashMap<>();
            config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            config.put(ConsumerConfig.GROUP_ID_CONFIG, "lag-check-" + UUID.randomUUID());
            config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
            config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
                List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic, TIMEOUT);
                if (partitionInfos == null || partitionInfos.isEmpty()) {
                    return 0;
                }
                List<TopicPartition> partitions = partitionInfos.stream()
                        .map(p -> new TopicPartition(topic, p.partition()))
                        .toList();
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions, TIMEOUT);

                long lag = 0;
                for (TopicPartition tp : partitions) {
                    long end = endOffsets.getOrDefault(tp, 0L);
                    OffsetAndMetadata committedOffset = committed.get(tp);
                    long consumedOffset = committedOffset != null ? committedOffset.offset() : 0L;
                    lag += Math.max(0, end - consumedOffset);
                }
                return lag;
            }
        } catch (Exception e) {
            log.warn("[KafkaConsumerLagChecker] topic='{}' group='{}' lag 조회 실패", topic, consumerGroupId, e);
            return -1;
        }
    }
}