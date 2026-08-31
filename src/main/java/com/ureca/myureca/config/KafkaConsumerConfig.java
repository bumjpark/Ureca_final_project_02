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
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import com.ureca.myureca.consumer.CouponIssuedEventDeserializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka Consumer 설정.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>배치 크기: max.poll.records=5000 (처리량 검증용 실험치, max.poll.interval.ms도 비례 확대해 안전마진 유지)</li>
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
     * <p>현재: {@link com.ureca.myureca.consumer.DltPublishingFailureRecoverer}
     * (재시도 모두 실패한 메시지를 DLT 토픽으로 실제 이적재, {@code @Primary}로 지정됨).
     * DLT 발행 자체가 실패하면 {@link com.ureca.myureca.consumer.LoggingFailureRecoverer}로
     * 폴백해 구조화된 로그를 남긴다 (UBM-37).
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

        // 처리량 검증용 실험치: 배치를 5000건까지 확대.
        // max.partition.fetch.bytes(기본 1MB)를 안 건드린 상태라, CouponIssuedEvent JSON
        // 1건이 레코드 오버헤드 포함 약 200바이트인 걸 감안하면 1MB / 200B ≈ 5000건이
        // 파티션 하나당 한 번의 poll()에서 실제로 가져올 수 있는 물리적 상한이다.
        // 이보다 더 키우려면 max.partition.fetch.bytes/fetch.max.bytes도 같이 올려야 의미가 있다.
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000);

        // 배치가 20 → 5000건(250배)으로 커진 만큼, 재시도(최악 4회 반복) 포함 최악 처리 시간도
        // 비례해서 커지므로 max.poll.interval.ms도 같이 늘려 안전마진을 유지한다.
        // (이 값을 초과하면 리밸런싱이 발생하여 동일 메시지 중복 처리 위험 증가)
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000);

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
     *   <li>기본 FixedBackOff(1초, 3회): 순간적인 DB 커넥션 문제만 커버. 길면 파티션 랙 적체 위험.</li>
     *   <li>락 경합(데드락/락 대기 타임아웃)만 예외적으로 지수 백오프 — {@link #transientLockBackOff()} 참고.</li>
     *   <li>DataIntegrityViolationException: non-retryable 등록 — 재시도해도 UNIQUE 제약은 해소되지 않음.</li>
     *   <li>재시도 모두 실패 시: {@link ConsumerFailureRecoverer}로 위임(현재 @Primary는 DLT 이적재).</li>
     * </ul>
     */
    private DefaultErrorHandler buildErrorHandler() {
        // recoverer: ConsumerFailureRecoverer 인터페이스 구현체(현재: DltPublishingFailureRecoverer)를 위임 호출
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, ex) -> failureRecoverer.recover(record, ex),
                new FixedBackOff(1_000L, 3L) // interval=1초, maxAttempts=3
        );

        // DataIntegrityViolationException은 재시도 불필요 — 즉시 recoverer로 넘기지 않고 스킵
        // (CouponIssuedEventProcessor에서 이미 catch하여 정상 종료하므로 여기까지 도달하는 경우는 드묾)
        errorHandler.addNotRetryableExceptions(DataIntegrityViolationException.class);

        // 락 경합만 다른 백오프를 쓴다. null을 돌려주면 위 기본 FixedBackOff가 그대로 적용된다.
        errorHandler.setBackOffFunction(
                (record, ex) -> isTransientLockFailure(ex) ? transientLockBackOff() : null);

        return errorHandler;
    }

    /**
     * 데드락(MySQL 1213)·락 대기 타임아웃(1205) 전용 백오프.
     *
     * <p><b>왜 기본 백오프로는 부족한가</b>: 이 둘은 DB가 "지금은 안 되니 다시 시도하라"고 알려주는
     * 일시적 오류다. 그런데 기본값(1초 간격 3회)은 3초 안에 소진되므로, DB가 잠깐이라도 길게
     * 붐비면 곧바로 DLT로 넘어간다 — 유실은 아니지만(DLT → {@code reconciliation_log}(DLT_REPROCESS)
     * → {@code ReconciliationAutoRetryScheduler} 60초 주기 자동 재시도) 몇 초 뒤면 성공했을 건이
     * 훨씬 무거운 경로를 한 바퀴 돌고 최대 1분 이상 지연된다. 2026-08-31 실측에서 스케줄러가
     * DB를 2분간 붐비게 만들었을 때 이 경로로 대량 유입되는 것을 확인했다.
     *
     * <p>0.2초에서 시작해 2배씩(최대 2초), 총 6회까지 — 누적 약 7초. 파티션 랙이 밀리지 않도록
     * 상한을 두되, 짧은 경합은 제자리에서 흡수하고 넘어가게 하는 것이 목적이다.
     */
    private BackOff transientLockBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(200L, 2.0);
        backOff.setMaxInterval(2_000L);
        backOff.setMaxAttempts(6);
        return backOff;
    }

    /**
     * 예외 체인에 {@link TransientDataAccessException}(데드락·락 타임아웃·일시적 커넥션 문제)이
     * 있는지 확인한다. 리스너에서 올라온 예외는 {@code BatchListenerFailedException} 등으로
     * 여러 겹 감싸여 있으므로 원인 체인을 끝까지 따라가야 한다.
     */
    private boolean isTransientLockFailure(Exception ex) {
        for (Throwable t = ex; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof TransientDataAccessException) {
                return true;
            }
        }
        return false;
    }
}
