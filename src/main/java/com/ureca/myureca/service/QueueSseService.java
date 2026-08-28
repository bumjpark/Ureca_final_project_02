package com.ureca.myureca.service;

import com.ureca.myureca.dto.event.AdmittedPushEvent;
import com.ureca.myureca.dto.response.QueueStatusResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 대기열 실시간 SSE(Server-Sent Events) 스트림 및 푸시 알림 서비스.
 *
 * <p>클라이언트의 반복적인 폴링(Polling Storm)을 제거하고,
 * 대기열 통과(ADMITTED) 시점에 즉시 activeToken을 실시간 푸시한다.
 *
 * <p><b>이슈 #23 — 다중 인스턴스 대응</b>: {@code emitterMap}은 JVM 힙에 있는 인스턴스 로컬
 * 상태다. 반면 입장 스케줄러({@code QueueAdmissionScheduler})는 분산 락 때문에 정책당 한
 * 인스턴스에서만 돈다. 예전에는 그 인스턴스가 자신의 로컬 {@code emitterMap}에만 직접
 * push했는데, 다른 인스턴스에 SSE로 연결한 유저는 영원히 push를 못 받는 문제가 있었다.
 * 지금은 {@code sendAdmitted}가 로컬에 바로 쓰지 않고 Redis Pub/Sub 채널로 발행만 하고,
 * 모든 인스턴스가 그 채널을 구독해({@link #onMessage}) 각자 자신의 로컬 emitter에 유저가
 * 있으면 그 인스턴스가 push한다 — 어느 인스턴스가 발행했든, 어느 인스턴스에 유저가 연결돼
 * 있든 항상 전달된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueSseService implements MessageListener {

    /** 기본 타임아웃: 3분 (대기열 체류 최대 기대 시간) */
    private static final Long SSE_TIMEOUT = 180_000L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** key: "policyId:userId" — 이 인스턴스에 연결된 SSE만 들고 있다(다른 인스턴스 연결은 모름). */
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
     * 유저가 대기열을 통과했을 때 activeToken 실시간 푸시 요청.
     *
     * <p>이 인스턴스의 로컬 emitter에 직접 쓰지 않고 Redis 채널로 발행만 한다 — 실제 전달은
     * 채널을 구독 중인 모든 인스턴스가 {@link #onMessage}에서 각자 처리한다(이슈 #23).
     */
    public void sendAdmitted(Long policyId, Long userId, String activeToken) {
        try {
            AdmittedPushEvent event = new AdmittedPushEvent(policyId, userId, activeToken);
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(ADMITTED_CHANNEL, payload);
        } catch (Exception e) {
            // 발행 실패해도 폴링(get_queue_status.lua)이 폴백으로 살아있어 유저가 activeToken을
            // 아예 못 받는 건 아니다 — 실시간성만 잃을 뿐이라 여기서 예외를 전파하지 않는다.
            log.warn("ADMITTED 푸시 이벤트 발행 실패 (폴링 폴백으로 대체됨). policyId={}, userId={}",
                    policyId, userId, e);
        }
    }

    /** Redis Pub/Sub 채널명. */
    public static final String ADMITTED_CHANNEL = "sse:admitted";

    /**
     * {@link #ADMITTED_CHANNEL} 구독 콜백 — 모든 인스턴스에서 호출된다. 이 인스턴스가 해당
     * 유저의 SSE를 들고 있을 때만 실제로 push하고, 아니면 조용히 무시한다(다른 인스턴스의 몫).
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            AdmittedPushEvent event = objectMapper.readValue(message.getBody(), AdmittedPushEvent.class);
            deliverAdmittedLocally(event.policyId(), event.userId(), event.activeToken());
        } catch (Exception e) {
            log.warn("ADMITTED 푸시 메시지 처리 실패", e);
        }
    }

    private boolean deliverAdmittedLocally(Long policyId, Long userId, String activeToken) {
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
     *
     * <p>rank_update는 ADMITTED와 달리 순번이 바뀔 때마다 매우 자주 발생할 수 있어(대기열 전체
     * 갱신), 매번 Redis를 왕복시키면 그 자체가 새로운 부하가 된다. 그래서 이 메서드는 이슈 #23
     * 대상에서 제외했다 — 이 인스턴스에 연결된 유저에게만 best-effort로 전달하고, 다른
     * 인스턴스에 연결된 유저는 (기존과 동일하게) 다음 폴링에서 최신 순번을 받는다.
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
