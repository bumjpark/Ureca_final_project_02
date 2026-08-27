package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class QueueSseServiceTest {

    private QueueSseService sseService;

    private final Long POLICY_ID = 1L;
    private final Long USER_ID = 1001L;

    @BeforeEach
    void setUp() {
        sseService = new QueueSseService();
    }

    @Test
    void connect_호출_시_SseEmitter가_등록되고_활성_연결_수가_증가한다() {
        SseEmitter emitter = sseService.connect(POLICY_ID, USER_ID);

        assertThat(emitter).isNotNull();
        assertThat(sseService.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void sendAdmitted_호출_시_activeToken이_전송되고_연결이_완료_정리된다() {
        sseService.connect(POLICY_ID, USER_ID);
        assertThat(sseService.getActiveConnectionCount()).isEqualTo(1);

        boolean sent = sseService.sendAdmitted(POLICY_ID, USER_ID, "test-active-token-1234");

        assertThat(sent).isTrue();
        assertThat(sseService.getActiveConnectionCount()).isEqualTo(0);
    }

    @Test
    void 등록되지_않은_유저에게_sendAdmitted_호출_시_false를_반환한다() {
        boolean sent = sseService.sendAdmitted(POLICY_ID, 9999L, "token");

        assertThat(sent).isFalse();
    }

    @Test
    void sendRankUpdate_호출_시_순번_정보가_전송된다() {
        sseService.connect(POLICY_ID, USER_ID);

        boolean sent = sseService.sendRankUpdate(POLICY_ID, USER_ID, 15L, 30L);

        assertThat(sent).isTrue();
        // rank_update는 연결을 유지해야 함
        assertThat(sseService.getActiveConnectionCount()).isEqualTo(1);
    }
}
