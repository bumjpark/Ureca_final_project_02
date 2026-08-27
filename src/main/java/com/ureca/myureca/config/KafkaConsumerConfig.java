package com.ureca.myureca.config;

import com.ureca.myureca.consumer.ConsumerFailureRecoverer;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import com.ureca.myureca.consumer.CouponIssuedEventDeserializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka Consumer 설정.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>정합성 우선: max.poll.records=20으로 배치를 작게 유지, 실패 시 재처리 범위 최소화</li>
 *   <li>순서 보장: concurrency ≤ 파티션 수 (초과 시 일부 스레드 유휴 + 순서 보장 깨짐)</li>
 *   <li>멱등성: batch listener OFF, 이벤트 단위 트랜잭션 ({@link com.ureca.myureca.consumer.CouponIssuedEventProcessor})</li>
 *   <li>DLT 확장: recoverer를 {@link ConsumerFailureRecoverer} 인터페이스로 주입 — 다음 이슈에서 교체 시 이 Config만 수정</li>
 * </ul>
 *
 * <p>JDBC Batch Insert 불가 안내 (엔티티 수정 없이 현상만 기록):
 * CouponIssue, CouponHistory 모두 @GeneratedValue(strategy=IDENTITY)를 사용 중이라
 * Hibernate JDBC batch insert가 무력화된다 (IDENTITY 전략에서는 INSERT 직후 generated key 조회가 필요해 배치 불가).
 * 성능 개선 필요 시: SEQUENCE 전략 전환 + hibernate.jdbc.batch_size 설정 권장.
 * 현재는 정합성 우선 설계이므로 엔티티를 수정하지 않는다.
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * ConsumerFailureRecoverer 인터페이스로 주입받는다.
     *
     * <p>이번 이슈: LoggingFailureRecoverer (구조화된 실패 로그만 기록)
     * 다음 이슈: DltPublishingFailureRecoverer로 교체 — 이 Config의 @Primary 변경 또는
     * @ConditionalOnProperty로 전환하면 되며, Consumer 비즈니스 로직 수정 불필요.
     */
    private final ConsumerFailureRecoverer failureRecoverer;
    private final ObjectMapper objectMapper;

    public KafkaConsumerConfig(ConsumerFailureRecoverer failureRecoverer, ObjectMapper objectMapper) {
        this.failureRecoverer = failureRecoverer;
        this.objectMapper = objectMapper;
    }

    @Bean
    public ConsumerFactory<String, CouponIssuedEvent> couponIssuedEventConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // 정합성 우선: 배치를 20건으로 제한.
        // 재시도 포함 최악 처리 시간 = 20건 × (처리시간 + 1초×2 재시도) ≈ 40~60초
        // → max.poll.interval.ms(120초)보다 충분히 작음
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 20);

        // max.poll.interval.ms: 2분 (20건 × 최악 재시도 3초 × 여유 2배)
        // 이 값을 초과하면 리밸런싱이 발생하여 동일 메시지 중복 처리 위험 증가
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 120_000);

        // ErrorHandlingDeserializer로 역직렬화 오류를 Consumer 레벨에서 처리
        // 역직렬화 실패 시 원본 예외를 DeserializationException으로 래핑하여
        // DefaultErrorHandler의 non-retryable 대상으로 바로 넘긴다
        CouponIssuedEventDeserializer valueDeserializer = new CouponIssuedEventDeserializer(objectMapper);

        ErrorHandlingDeserializer<CouponIssuedEvent> errorHandlingDeserializer =
                new ErrorHandlingDeserializer<>(valueDeserializer);

        return new DefaultKafkaConsumerFactory<>(
                config,
                new StringDeserializer(),
                errorHandlingDeserializer
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CouponIssuedEvent> couponIssuedEventListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CouponIssuedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(couponIssuedEventConsumerFactory());

        // batch listener: true — 배치로 수신 후 Consumer가 for-loop 순차 처리
        factory.setBatchListener(true);

        // AckMode: BATCH — 배치 내 전체 처리가 끝난 후 오프셋 커밋
        // (이벤트 단위 트랜잭션 분리와 조합하여 정합성 보장)
        factory.getContainerProperties().setAckMode(AckMode.BATCH);

        factory.setCommonErrorHandler(buildErrorHandler());
        return factory;
    }

    /**
     * DefaultErrorHandler 구성.
     *
     * <ul>
     *   <li>FixedBackOff(1초, 3회): 순간적인 DB 커넥션 문제만 커버. 길면 파티션 랙 적체 위험.</li>
     *   <li>DataIntegrityViolationException: non-retryable 등록 — 재시도해도 UNIQUE 제약은 해소되지 않음.</li>
     *   <li>3회 모두 실패 시: {@link ConsumerFailureRecoverer}로 위임 (현재: 구조화된 로그, 다음 이슈: DLT 전송)</li>
     * </ul>
     */
    private DefaultErrorHandler buildErrorHandler() {
        // recoverer: ConsumerFailureRecoverer 인터페이스 구현체(현재: LoggingFailureRecoverer)를 위임 호출
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, ex) -> failureRecoverer.recover(record, ex),
                new FixedBackOff(1_000L, 3L) // interval=1초, maxAttempts=3
        );

        // DataIntegrityViolationException은 재시도 불필요 — 즉시 recoverer로 넘기지 않고 스킵
        // (CouponIssuedEventProcessor에서 이미 catch하여 정상 종료하므로 여기까지 도달하는 경우는 드묾)
        errorHandler.addNotRetryableExceptions(DataIntegrityViolationException.class);

        return errorHandler;
    }
}
