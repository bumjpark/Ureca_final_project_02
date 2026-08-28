package com.ureca.myureca.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.dto.response.ComponentHealthResponse;
import com.ureca.myureca.dto.response.HealthResponse;
import com.ureca.myureca.support.OpsAlertNotifier;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfraHealthMonitorSchedulerTest {

    @Mock
    private HealthCheckService healthCheckService;
    @Mock
    private OpsAlertNotifier opsAlertNotifier;

    @InjectMocks
    private InfraHealthMonitorScheduler scheduler;

    private static final Map<String, ComponentHealthResponse> ALL_UP = Map.of(
            "mysql", ComponentHealthResponse.up(1),
            "redis", ComponentHealthResponse.up(1),
            "kafka", ComponentHealthResponse.up(1));

    private static final Map<String, ComponentHealthResponse> REDIS_DOWN = Map.of(
            "mysql", ComponentHealthResponse.up(1),
            "redis", ComponentHealthResponse.down(1500, "Currently not connected. Commands are rejected."),
            "kafka", ComponentHealthResponse.up(1));

    @BeforeEach
    void setUp() {
    }

    @Test
    void 시작_직후_정상이면_알림을_보내지_않는다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(ALL_UP));

        scheduler.monitor();

        verify(opsAlertNotifier, never()).alert(anyString(), anyString());
    }

    @Test
    void 시작_직후_이미_장애면_즉시_알린다() {
        when(healthCheckService.check()).thenReturn(HealthResponse.of(REDIS_DOWN));

        scheduler.monitor();

        verify(opsAlertNotifier, times(1)).alert(eq("인프라 장애 감지"), anyString());
    }

    @Test
    void 정상에서_장애로_전환된_시점에만_알린다() {
        when(healthCheckService.check())
                .thenReturn(HealthResponse.of(ALL_UP))
                .thenReturn(HealthResponse.of(REDIS_DOWN))
                .thenReturn(HealthResponse.of(REDIS_DOWN))
                .thenReturn(HealthResponse.of(REDIS_DOWN));

        scheduler.monitor(); // UP (기준값)
        scheduler.monitor(); // UP -> DOWN 전환, 알림 1회
        scheduler.monitor(); // DOWN 지속, 추가 알림 없음
        scheduler.monitor(); // DOWN 지속, 추가 알림 없음

        verify(opsAlertNotifier, times(1)).alert(eq("인프라 장애 감지"), anyString());
    }

    @Test
    void 장애에서_복구로_전환된_시점에_복구_알림을_보낸다() {
        when(healthCheckService.check())
                .thenReturn(HealthResponse.of(REDIS_DOWN))
                .thenReturn(HealthResponse.of(ALL_UP));

        scheduler.monitor(); // DOWN (기준값 + 즉시 알림)
        scheduler.monitor(); // DOWN -> UP 전환, 복구 알림

        verify(opsAlertNotifier, times(1)).alert(eq("인프라 장애 감지"), anyString());
        verify(opsAlertNotifier, times(1)).alert(eq("인프라 복구됨"), anyString());
    }
}
