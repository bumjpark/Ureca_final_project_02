package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 이슈 #23: SSE ADMITTED 푸시가 로컬 emitterMap에 직접 쓰지 않고 Redis Pub/Sub 채널로
 * 발행/구독되는지 검증한다 — 발행({@link QueueSseService#sendAdmitted})과 수신 후 로컬 전달
 * ({@link QueueSseService#onMessage})을 각각 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueSseServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private ObjectMapper objectMapper;
    private QueueSseService sseService;

    private final Long POLICY_ID = 1L;
    private final Long USER_ID = 1001L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sseService = new QueueSseService(redisTemplate, objectMapper);
    }

    @Test
    void connect_호출_시_SseEmitter가_등록되고_활성_연결_수가_증가한다() {
        SseEmitter emitter = sseService.connect(POLICY_ID, USER_ID);

        assertThat(emitter).isNotNull();
        assertThat(sseService.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void sendAdmitted는_로컬에_직접_쓰지_않고_Redis_채널로만_발행한다() {
        sseService.connect(POLICY_ID, USER_ID);

        sseService.sendAdmitted(POLICY_ID, USER_ID, "test-active-token-1234");

        // 이슈 #23 핵심: 발행만 하고, 로컬 emitter는 이 인스턴스가 직접 완료 처리하지 않는다
        // (구독 콜백 onMessage를 통해서만 완료된다) — 그래서 발행 직후엔 연결이 그대로 남아있다.
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(QueueSseService.ADMITTED_CHANNEL), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"activeToken\":\"test-active-token-1234\"");
        assertThat(sseService.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void onMessage로_수신하면_이_인스턴스에_연결된_유저에게_실제로_push하고_연결을_정리한다() {
        sseService.connect(POLICY_ID, USER_ID);
        Message message = redisMessage(
                "{\"policyId\":1,\"userId\":1001,\"activeToken\":\"test-active-token-1234\"}");

        sseService.onMessage(message, null);

        assertThat(sseService.getActiveConnectionCount()).isEqualTo(0);
    }

    @Test
    void onMessage로_수신했지만_이_인스턴스에_연결이_없으면_조용히_무시한다() {
        // 다른 인스턴스에 연결된 유저 몫 — 예외 없이 그냥 지나가야 한다.
        Message message = redisMessage(
                "{\"policyId\":1,\"userId\":9999,\"activeToken\":\"token\"}");

        sseService.onMessage(message, null);

        assertThat(sseService.getActiveConnectionCount()).isEqualTo(0);
    }

    @Test
    void onMessage_페이로드가_깨져도_예외를_전파하지_않는다() {
        Message message = redisMessage("not-a-json");

        org.assertj.core.api.Assertions.assertThatCode(() -> sseService.onMessage(message, null))
                .doesNotThrowAnyException();
    }

    @Test
    void sendRankUpdate_호출_시_이_인스턴스에_연결돼_있으면_순번_정보가_전송된다() {
        sseService.connect(POLICY_ID, USER_ID);

        boolean sent = sseService.sendRankUpdate(POLICY_ID, USER_ID, 15L, 30L);

        assertThat(sent).isTrue();
        // rank_update는 연결을 유지해야 함
        assertThat(sseService.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void sendRankUpdate_호출_시_연결이_없으면_false를_반환한다() {
        boolean sent = sseService.sendRankUpdate(POLICY_ID, 9999L, 15L, 30L);

        assertThat(sent).isFalse();
    }

    private Message redisMessage(String body) {
        Message message = org.mockito.Mockito.mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
