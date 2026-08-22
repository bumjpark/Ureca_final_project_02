package com.ureca.myureca.service;

import com.ureca.myureca.exception.TooManyRequestsException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 대기열 진입 시 동일 유저의 매크로/광클(연타) 공격을 0.001ms 만에 인앱 차단하는 RateLimiter.
 * Redis 네트워크 대역폭과 CPU 자원을 보호한다.
 */
@Component
public class QueueRateLimiter {

    /** 유저별 최소 요청 허용 간격 (밀리초): 1000ms (1초) */
    private static final long MIN_REQUEST_INTERVAL_MS = 1000L;

    /** key: "policyId:userId" -> value: lastRequestTimeMillis */
    private final Map<String, Long> lastRequestTimes = new ConcurrentHashMap<>();

    /**
     * 유저의 대기열 진입 요청 주기를 검증한다.
     * 1초 이내 재호출 시 TooManyRequestsException(429) 발생.
     */
    public void checkRateLimit(Long policyId, Long userId) {
        String key = policyId + ":" + userId;
        long now = System.currentTimeMillis();

        Long lastTime = lastRequestTimes.put(key, now);
        if (lastTime != null && (now - lastTime) < MIN_REQUEST_INTERVAL_MS) {
            throw new TooManyRequestsException();
        }

        // 메모리 릭 방지: 맵 크기가 커지면 10초 이상 지난 오래된 엔트리 정리
        if (lastRequestTimes.size() > 50000) {
            lastRequestTimes.entrySet().removeIf(entry -> now - entry.getValue() > MIN_REQUEST_INTERVAL_MS * 10);
        }
    }

    /** 테스트용 클리어 */
    public void reset() {
        lastRequestTimes.clear();
    }
}
