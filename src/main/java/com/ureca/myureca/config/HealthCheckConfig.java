package com.ureca.myureca.config;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 헬스체크 전용 인프라 빈.
 *
 * <p>AdminClient와 스레드풀을 헬스체크 호출마다 새로 만들고 버리면, 부하테스트 중
 * 모니터링이 자주 찌를 때 커넥션/스레드 생성 비용이 계속 반복된다. 애플리케이션
 * 생명주기 동안 하나만 만들어 재사용한다.</p>
 */
@Configuration
public class HealthCheckConfig {

    /**
     * Kafka 브로커 상태 조회용 AdminClient. 호출마다 새로 만들지 않고 싱글턴으로 재사용한다.
     *
     * <p>이 프로젝트는 {@code KafkaProducerConfig}처럼 Kafka를 Boot 자동설정에 기대지 않고
     * {@code spring.kafka.bootstrap-servers} 값을 직접 주입받아 수동으로 구성하는 방식을
     * 쓴다. 같은 관례를 따라 bootstrap-servers를 직접 주입받는다.</p>
     *
     * <p>2026-08-28 갱신: {@code KafkaAdmin} 빈은 이 클래스가 작성될 당시엔 정말 없었지만,
     * 지금은 {@link KafkaTopicConfig}가 토픽 파티션 수 영속화를 위해 별도로(같은 수동 구성
     * 관례로) 등록해뒀다. 이 AdminClient는 그것과는 별개로, 헬스체크 전용 짧은 타임아웃
     * 설정을 위해 여전히 직접 만들어 쓴다.</p>
     *
     * <p>{@code request.timeout.ms}/{@code default.api.timeout.ms}를 짧게 못박아 둔다.
     * 기본값(120초)을 그대로 두면, 브로커가 응답 없을 때 내부적으로 재시도가 오래 걸리다가
     * 애플리케이션 종료 시 {@code close()}가 그 재시도가 끝날 때까지 최대 2분 가까이
     * 물고 늘어질 수 있다 — Kafka가 죽어있을 때 앱 종료 자체가 지연되는 것을 막기 위함이다.</p>
     */
    @Bean(destroyMethod = "close")
    public AdminClient healthCheckKafkaAdminClient(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        return AdminClient.create(config);
    }

    /**
     * MySQL/Redis/Kafka 점검을 병렬로 돌리기 위한 전용 실행기.
     * 애플리케이션의 다른 작업과 스레드를 공유하지 않도록 분리한다.
     * I/O 대기가 대부분이라 가상 스레드를 사용한다.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService healthCheckExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}