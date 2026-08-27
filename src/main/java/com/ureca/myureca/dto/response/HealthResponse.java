package com.ureca.myureca.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

public record HealthResponse(
        String status,
        LocalDateTime checkedAt,
        Map<String, ComponentHealthResponse> components
) {

    public static HealthResponse of(Map<String, ComponentHealthResponse> components) {
        boolean allUp = components.values().stream().allMatch(ComponentHealthResponse::isUp);
        return new HealthResponse(allUp ? "UP" : "DOWN", LocalDateTime.now(), components);
    }

    /**
     * liveness 전용 — 인프라(DB/Redis/Kafka)는 확인하지 않고 "프로세스가 응답 가능한가"만 나타낸다.
     * 로드밸런서/오케스트레이터가 짧은 주기로 반복 호출해도 부담이 없어야 하는 경로에서 쓴다.
     */
    public static HealthResponse liveness() {
        return new HealthResponse("UP", LocalDateTime.now(), Map.of());
    }

    public boolean isUp() {
        return "UP".equals(status);
    }
}
