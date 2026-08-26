package com.ureca.myureca.service;

import com.ureca.myureca.dto.response.ComponentHealthResponse;
import com.ureca.myureca.dto.response.HealthResponse;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.Optional;

/**
 * 인프라(MySQL / Redis / Kafka) 연결 상태를 점검한다 (E. 인프라 · 헬스체크).
 *
 * <p>k6 부하테스트 시작 전 사전 점검, 운영 중 모니터링, Redis 재구성 이전 상태
 * 확인 등에서 재사용된다. 부하가 몰리는 상황에서 헬스체크 자체가 병목이 되거나
 * 잘못된 신호를 주지 않도록 다음 4가지를 지킨다.</p>
 *
 * <ol>
 *   <li>MySQL 점검은 실제 발급 트래픽과 완전히 분리된 별도의 작은 커넥션 풀을 쓴다.
 *       그래야 "DB가 죽음"과 "내 트래픽이 풀을 다 씀"을 구분할 수 있다.</li>
 *   <li>Kafka AdminClient는 호출마다 새로 만들지 않고 싱글턴을 재사용한다.</li>
 *   <li>3개 점검을 순차가 아니라 병렬로 실행하고, 개별 타임아웃을 건다.</li>
 *   <li>결과를 짧게 캐싱해 짧은 주기로 반복 호출돼도 매번 인프라를 다시 찌르지 않는다.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;
    private static final long KAFKA_DESCRIBE_TIMEOUT_MS = 2000;
    private static final long PER_CHECK_TIMEOUT_MS = 3000;
    private static final long CACHE_TTL_MS = 2000;

    private final DataSourceProperties dataSourceProperties;
    private final StringRedisTemplate redisTemplate;
    private final AdminClient kafkaAdminClient;
    private final ExecutorService healthCheckExecutor;

    /** 발급 트래픽이 쓰는 메인 풀과는 별개인, 헬스체크 전용 소형 풀. */
    private HikariDataSource healthCheckDataSource;

    private final AtomicReference<CachedHealth> cache = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    private record CachedHealth(HealthResponse response, long computedAtMillis) {
        boolean isFresh(long now) {
            return now - computedAtMillis < CACHE_TTL_MS;
        }
    }

    @PostConstruct
    void initHealthCheckDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceProperties.determineUrl());
        ds.setUsername(dataSourceProperties.determineUsername());
        ds.setPassword(dataSourceProperties.determinePassword());
        ds.setDriverClassName(dataSourceProperties.determineDriverClassName());
        ds.setPoolName("health-check-pool");
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(0);
        // 풀이 없거나 DB가 응답 없을 때, 메인 트래픽처럼 대기열에서 오래 기다리지 않고
        // 짧게 실패해서 "DOWN"을 빠르게 판정하기 위한 타임아웃.
        ds.setConnectionTimeout(1000);
        ds.setValidationTimeout(1000);
        this.healthCheckDataSource = ds;
    }

    @PreDestroy
    void closeHealthCheckDataSource() {
        if (healthCheckDataSource != null) {
            healthCheckDataSource.close();
        }
    }

    
    
    public HealthResponse check() {
        CachedHealth cached = cache.get();
        if (cached != null && cached.isFresh(System.currentTimeMillis())) {
            return cached.response();
        }

        if (!refreshLock.tryLock()) {
            // 이미 다른 요청이 갱신 중이면 새로 계산을 기다리게 하지 않고
            // 직전 값을 그대로 돌려준다 (약간 stale해도 헬스체크 목적엔 충분하다).
            // 캐시가 아예 없는 콜드 스타트 시점에만 락을 기다린다.
            CachedHealth existing = cache.get();
            if (existing != null) {
                return existing.response();
            }
            refreshLock.lock();
            try {
                return computeAndCache();
            } finally {
                refreshLock.unlock();
            }
        }

        try {
            cached = cache.get();
            if (cached != null && cached.isFresh(System.currentTimeMillis())) {
                return cached.response();
            }
            return computeAndCache();
        } finally {
            refreshLock.unlock();
        }
    }

    private HealthResponse computeAndCache() {
        HealthResponse fresh = computeFresh();
        cache.set(new CachedHealth(fresh, System.currentTimeMillis()));
        return fresh;
    }

    private HealthResponse computeFresh() {
        CompletableFuture<ComponentHealthResponse> mysql = runWithTimeout(this::checkMysql, "MySQL");
        CompletableFuture<ComponentHealthResponse> redis = runWithTimeout(this::checkRedis, "Redis");
        CompletableFuture<ComponentHealthResponse> kafka = runWithTimeout(this::checkKafka, "Kafka");

        CompletableFuture.allOf(mysql, redis, kafka).join();

        Map<String, ComponentHealthResponse> components = new LinkedHashMap<>();
        components.put("mysql", mysql.join());
        components.put("redis", redis.join());
        components.put("kafka", kafka.join());
        return HealthResponse.of(components);
    }

    /**
     * 개별 점검을 병렬 실행하되, 응답 자체가 예상 밖으로 오래 걸릴 경우
     * (예: Redis 클라이언트 자체 타임아웃이 크게 잡혀 있는 경우) 이 future 레벨에서
     * 강제로 DOWN 처리해 /health 응답이 무한정 늘어지지 않게 막는 안전판이다.
     */
    private CompletableFuture<ComponentHealthResponse> runWithTimeout(
            Supplier<ComponentHealthResponse> check, String label) {
        return CompletableFuture.supplyAsync(check, healthCheckExecutor)
                .orTimeout(PER_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> ComponentHealthResponse.down(
                        PER_CHECK_TIMEOUT_MS, label + " 응답 지연 (timeout)"));
    }

    ComponentHealthResponse checkMysql() {
        long start = System.currentTimeMillis();
        try (Connection connection = healthCheckDataSource.getConnection()) {
            boolean valid = connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
            long latency = elapsed(start);
            return valid
                    ? ComponentHealthResponse.up(latency)
                    : ComponentHealthResponse.down(latency, "connection.isValid()=false");
        } catch (Exception e) {
            log.warn("[HealthCheck] MySQL 점검 실패", e);
            return ComponentHealthResponse.down(elapsed(start), describe(e));
        }
    }

    ComponentHealthResponse checkRedis() {
        long start = System.currentTimeMillis();
        RedisConnection connection = null;
        try {
            connection = redisTemplate.getRequiredConnectionFactory().getConnection();
            String pong = connection.ping();
            long latency = elapsed(start);
            return "PONG".equalsIgnoreCase(pong)
                    ? ComponentHealthResponse.up(latency)
                    : ComponentHealthResponse.down(latency, "unexpected reply: " + pong);
        } catch (Exception e) {
            log.warn("[HealthCheck] Redis 점검 실패", e);
            return ComponentHealthResponse.down(elapsed(start), describe(e));
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception closeEx) {
                    log.debug("[HealthCheck] Redis connection close 실패", closeEx);
                }
            }
        }
    }

    ComponentHealthResponse checkKafka() {
        long start = System.currentTimeMillis();
        try {
            DescribeClusterResult result = kafkaAdminClient.describeCluster();
            int nodeCount = result.nodes().get(KAFKA_DESCRIBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).size();
            long latency = elapsed(start);
            return nodeCount > 0
                    ? ComponentHealthResponse.up(latency)
                    : ComponentHealthResponse.down(latency, "브로커 노드 0개");
        } catch (Exception e) {
            log.warn("[HealthCheck] Kafka 점검 실패", e);
            return ComponentHealthResponse.down(elapsed(start), describe(e));
        }
    }

    private long elapsed(long startMillis) {
        return System.currentTimeMillis() - startMillis;
    }

    private String describe(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }
}
