package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ureca.myureca.dto.response.ComponentHealthResponse;
import com.ureca.myureca.dto.response.HealthResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * MySQL 쪽은 실제 커넥션 풀(HikariDataSource)을 내부에서 직접 만들기 때문에,
 * "정상 연결" 경로는 실제 DB가 필요해 여기서는 다루지 않는다(로컬 docker compose 기동 후
 * /api/health를 직접 호출해 확인). 여기서는 도달 불가능한 주소로 빠르게 DOWN 판정되는지,
 * 그리고 병렬 실행·캐싱이 의도대로 동작하는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class HealthCheckServiceTest {

    @Mock
    private DataSourceProperties dataSourceProperties;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisConnectionFactory redisConnectionFactory;
    @Mock
    private RedisConnection redisConnection;

    private AdminClient unreachableKafkaAdminClient;
    private ExecutorService executor;

    private AdminClient newUnreachableKafkaAdminClient() {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:19999");
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        return AdminClient.create(config);
    }

    private HealthCheckService newService() {
        // 아무도 듣고 있지 않은 로컬 주소 → 실제 DB/브로커 없이도 "DOWN" 경로를 빠르게 검증 가능
        when(dataSourceProperties.determineUrl()).thenReturn("jdbc:mysql://127.0.0.1:1/health_check_test");
        when(dataSourceProperties.determineUsername()).thenReturn("test");
        when(dataSourceProperties.determinePassword()).thenReturn("test");
        when(dataSourceProperties.determineDriverClassName()).thenReturn("com.mysql.cj.jdbc.Driver");

        unreachableKafkaAdminClient = newUnreachableKafkaAdminClient();
        executor = Executors.newVirtualThreadPerTaskExecutor();

        HealthCheckService service = new HealthCheckService(
                dataSourceProperties, redisTemplate, unreachableKafkaAdminClient, executor);
        service.initHealthCheckDataSource();
        return service;
    }

    @AfterEach
    void tearDown() {
        if (unreachableKafkaAdminClient != null) {
            // 인자 없는 close()는 브로커 응답이 없을 때 내부 재시도가 끝날 때까지 기다리다
            // 최대 수십 초~2분까지 걸릴 수 있다 — 명시적으로 짧게 끊어준다.
            unreachableKafkaAdminClient.close(Duration.ofSeconds(1));
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void MySQL_연결할_수_없으면_DOWN을_빠르게_반환한다() {
        HealthCheckService service = newService();

        long start = System.currentTimeMillis();
        ComponentHealthResponse response = service.checkMysql();
        long took = System.currentTimeMillis() - start;

        assertThat(response.isUp()).isFalse();
        // 커넥션 풀 connectionTimeout(1s) 안에서 실패해야 한다 — 메인 트래픽 풀처럼
        // 오래 대기하지 않는다는 것이 이번 개선의 핵심.
        assertThat(took).isLessThan(2000);
    }

    @Test
    void Kafka_브로커에_붙을_수_없으면_DOWN을_반환한다() {
        HealthCheckService service = newService();

        ComponentHealthResponse response = service.checkKafka();

        assertThat(response.isUp()).isFalse();
        assertThat(response.detail()).isNotBlank();
    }

    @Test
    void Redis가_PONG으로_응답하면_UP을_반환한다() {
        HealthCheckService service = newService();
        when(redisTemplate.getRequiredConnectionFactory()).thenReturn(redisConnectionFactory);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        ComponentHealthResponse response = service.checkRedis();

        assertThat(response.isUp()).isTrue();
    }

    @Test
    void 세_컴포넌트_점검이_병렬로_수행되고_결과가_짧게_캐싱된다() {
        HealthCheckService service = newService();
        when(redisTemplate.getRequiredConnectionFactory()).thenReturn(redisConnectionFactory);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        long start = System.currentTimeMillis();
        HealthResponse first = service.check();
        long took = System.currentTimeMillis() - start;

        // MySQL/Kafka DOWN 판정이 순차였다면 각 타임아웃이 더해져 훨씬 오래 걸렸을 것 —
        // 병렬 실행이라면 가장 오래 걸리는 점검 하나의 시간 정도로 끝나야 한다.
        assertThat(took).isLessThan(3000);
        assertThat(first.isUp()).isFalse();
        assertThat(first.components().get("redis").isUp()).isTrue();
        assertThat(first.components().get("mysql").isUp()).isFalse();
        assertThat(first.components().get("kafka").isUp()).isFalse();

        // 캐시 TTL(2s) 안에 다시 호출하면 재계산 없이 같은 인스턴스를 그대로 돌려준다.
        HealthResponse second = service.check();
        assertThat(second).isSameAs(first);
    }
}