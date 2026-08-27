package com.ureca.myureca.service;

import com.ureca.myureca.dto.response.ComponentHealthResponse;
import com.ureca.myureca.dto.response.HealthResponse;
import com.ureca.myureca.support.OpsAlertNotifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인프라 헬스체크({@code GET /api/health?deep=true}, {@link HealthCheckService})를 주기적으로
 * 폴링해서 상태가 바뀐 시점(UP→DOWN, DOWN→UP)에만 알림을 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfraHealthMonitorScheduler {

    private final HealthCheckService healthCheckService;
    private final OpsAlertNotifier opsAlertNotifier;

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
                opsAlertNotifier.alert("인프라 장애 감지", describeDown(health));
            }
            return;
        }

        boolean wasUp = lastKnownUp.getAndSet(nowUp);
        if (wasUp && !nowUp) {
            opsAlertNotifier.alert("인프라 장애 감지", describeDown(health));
        } else if (!wasUp && nowUp) {
            opsAlertNotifier.alert("인프라 복구됨", "MySQL/Redis/Kafka 모두 정상 복귀");
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
