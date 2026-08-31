package com.ureca.myureca.dto.response;

public record ComponentHealthResponse(
        String status,
        long latencyMs,
        String detail
) {

    public static ComponentHealthResponse up(long latencyMs) {
        return new ComponentHealthResponse("UP", latencyMs, null);
    }

    public static ComponentHealthResponse down(long latencyMs, String detail) {
        return new ComponentHealthResponse("DOWN", latencyMs, detail);
    }

    public boolean isUp() {
        return "UP".equals(status);
    }
}
