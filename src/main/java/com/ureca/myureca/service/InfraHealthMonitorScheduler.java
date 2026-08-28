package com.ureca.myureca.service;

import com.ureca.myureca.dto.response.ComponentHealthResponse;
import com.ureca.myureca.dto.response.HealthResponse;
import com.ureca.myureca.support.OpsAlertNotifier;
import com.ureca.myureca.support.RedisKeys;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인프라 헬스체크({@code GET /api/health?deep=true}, {@link HealthCheckService})를 주기적으로
 * 폴링해서 상태가 바뀐 시점(UP→DOWN, DOWN→UP)에만 알림을 보낸다.
 *
 * <p>상태 전환 판정(lastKnownUp)은 인스턴스마다 독립적으로 폴링하므로 일부러 JVM 로컬로 둔다 —
 * 각 인스턴스가 "내가 보기엔 방금 전환됐다"고 판단하는 것 자체는 정상이다. 문제는 그 다음:
 * 인스턴스가 N대면 알림도 N번 나간다. 그래서 실제 발송 직전에만 Redis 분산 락
 * ({@link RedisKeys#lockAlert}, {@code QueueAdmissionScheduler}와 동일한 SETNX 패턴)으로
 * "이번 전환에 대해 실제로 알릴 인스턴스"를 하나로 조율한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfraHealthMonitorScheduler {

    /** 알림 중복 방지 락 TTL. 폴링 주기(기본 5초)보다 넉넉히 잡아, 인접한 폴링 틱에서 여러
     *  인스턴스가 거의 동시에 같은 전환을 감지해도 한 번만 알림이 나가게 한다. */
    private static final Duration ALERT_LOCK_TTL = Duration.ofSeconds(10);

    private final HealthCheckService healthCheckService;
    private final OpsAlertNotifier opsAlertNotifier;
    private final StringRedisTemplate redisTemplate;

    private final AtomicBoolean lastKnownUp = new AtomicBoolean(true);
    private volatile boolean initialized = false;

    @Scheduled(fixedDelayString = "${infra.health-monitor.interval-ms:5000}")
    public void monitor() {
        HealthResponse health = healthCheckService.check();
        boolean nowUp = health.isUp();

        if (!initialized) {
            // 앱 시작 직후 첫 틱은 "전환"이 아니라 기준값 설정으로만 취급한다.
            // 다만 시작하자마자 이미 DOWN이면 그 자체는 즉시 알려야 한다.
            initialized = true;
            lastKnownUp.set(nowUp);
            if (!nowUp) {
                alertOnce("infra-down", "인프라 장애 감지", describeDown(health));
            }
            return;
        }

        boolean wasUp = lastKnownUp.getAndSet(nowUp);
        if (wasUp && !nowUp) {
            alertOnce("infra-down", "인프라 장애 감지", describeDown(health));
        } else if (!wasUp && nowUp) {
            alertOnce("infra-up", "인프라 복구됨", "MySQL/Redis/Kafka 모두 정상 복귀");
        }
    }

    /**
     * 다중 인스턴스 환경에서 이번 상태 전환에 대해 실제로 알림을 발송할 인스턴스를 하나로
     * 조율한다. 락 획득에 실패하면(다른 인스턴스가 이미 발송 중) 조용히 건너뛴다 — 알림
     * 자체가 유실되는 게 아니라 "이미 다른 인스턴스가 보냈다"는 뜻이므로 정상 동작이다.
     */
    private void alertOnce(String alertKind, String title, String detail) {
        String lockKey = RedisKeys.lockAlert(alertKind);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", ALERT_LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                opsAlertNotifier.alert(title, detail);
            } else {
                log.debug("[InfraHealthMonitor] 다른 인스턴스가 이미 알림을 발송함 - alertKind={}", alertKind);
            }
        } catch (Exception e) {
            // Redis 자체가 불안정한 상황(장애 감지 알림을 보내려는 바로 그 순간일 수 있음)에서
            // 락 획득이 실패해서 알림까지 놓치면 안 된다 - 중복 발송 가능성을 감수하고 그냥 보낸다.
            log.warn("[InfraHealthMonitor] 알림 중복 방지 락 획득 실패 - 중복 발송 감수하고 알림 발송. alertKind={}",
                    alertKind, e);
            opsAlertNotifier.alert(title, detail);
        }
    }

    private String describeDown(HealthResponse health) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ComponentHealthResponse> entry : health.components().entrySet()) {
            ComponentHealthResponse component = entry.getValue();
            if (!component.isUp()) {
                sb.append(entry.getKey()).append("=DOWN(").append(component.detail()).append(") ");
            }
        }
        return sb.isEmpty() ? "원인 불명" : sb.toString().trim();
    }
}
