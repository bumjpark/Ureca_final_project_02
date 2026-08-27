package com.ureca.myureca.service;

import com.ureca.myureca.dto.response.QueueStatusResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 실시간 SSE(Server-Sent Events) 스트림 및 푸시 알림 서비스.
 *
 * <p>클라이언트의 반복적인 폴링(Polling Storm)을 제거하고,
 * 대기열 통과(ADMITTED) 시점에 즉시 activeToken을 실시간 푸시한다.
 */
@Slf4j
@Service
public class QueueSseService {

    /** 기본 타임아웃: 3분 (대기열 체류 최대 기대 시간) */
    private static final Long SSE_TIMEOUT = 180_000L;

    /** key: "policyId:userId" */
    private final Map<String, SseEmitter> emitterMap = new ConcurrentHashMap<>();

    /**
     * SSE 연결 수립 및 등록.
     */
    public SseEmitter connect(Long policyId, Long userId) {
        String key = buildKey(policyId, userId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        emitterMap.put(key, emitter);

        emitter.onCompletion(() -> {
            log.debug("SSE 연결 종료: {}", key);
            emitterMap.remove(key);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE 타임아웃: {}", key);
            emitterMap.remove(key);
            emitter.complete();
        });
        emitter.onError(e -> {
            log.debug("SSE 에러: {}, msg={}", key, e.getMessage());
            emitterMap.remove(key);
        });

        // 초기 연결 성공 이벤트 전송 (연결 확인용)
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("SSE stream connected for policy " + policyId + ", user " + userId, MediaType.TEXT_PLAIN));
        } catch (IOException e) {
            emitterMap.remove(key);
            log.warn("SSE 초기 연결 이벤트 전송 실패: {}", key);
        }

        return emitter;
    }

    /**
     * 유저가 대기열을 통과했을 때 activeToken 실시간 푸시.
     */
    public boolean sendAdmitted(Long policyId, Long userId, String activeToken) {
        String key = buildKey(policyId, userId);
        SseEmitter emitter = emitterMap.get(key);
        if (emitter == null) {
            return false;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("admitted")
                    .data(QueueStatusResponse.admitted(activeToken)));
            emitter.complete();
            emitterMap.remove(key);
            log.info("SSE ADMITTED 이벤트 푸시 성공: {}", key);
            return true;
        } catch (IOException e) {
            log.warn("SSE ADMITTED 이벤트 푸시 실패: {}", key, e);
            emitterMap.remove(key);
            return false;
        }
    }

    /**
     * 대기 순번 실시간 업데이트 푸시.
     */
    public boolean sendRankUpdate(Long policyId, Long userId, long rank, long estimatedWaitSeconds) {
        String key = buildKey(policyId, userId);
        SseEmitter emitter = emitterMap.get(key);
        if (emitter == null) {
            return false;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("rank_update")
                    .data(QueueStatusResponse.waiting(rank, estimatedWaitSeconds, 1.0)));
            return true;
        } catch (IOException e) {
            log.warn("SSE rank_update 이벤트 푸시 실패: {}", key, e);
            emitterMap.remove(key);
            return false;
        }
    }

    public int getActiveConnectionCount() {
        return emitterMap.size();
    }

    private String buildKey(Long policyId, Long userId) {
        return policyId + ":" + userId;
    }
}
