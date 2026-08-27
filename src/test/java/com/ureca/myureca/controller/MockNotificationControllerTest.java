package com.ureca.myureca.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 컨트롤러가 {@code WebAsyncTask}를 반환하도록 바뀌면서, 일반 동기 테스트처럼
 * perform() 한 번으로 끝나지 않는다. "비동기가 시작됐는지" 먼저 확인하고,
 * asyncDispatch()로 실제 완료된 결과를 다시 검증하는 2단계가 필요하다.
 */
@WebMvcTest(MockNotificationController.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mock.kakao.min-latency-ms=0",
        "mock.kakao.max-latency-ms=0"
})
class MockNotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 정상_요청이면_SENT_상태를_반환한다() throws Exception {
        String body = """
                {"userId": 1, "templateId": "COUPON_ISSUED", "message": "쿠폰이 발급되었습니다."}
                """;

        MvcResult asyncResult = mockMvc.perform(post("/api/mock/notifications/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.messageId").exists());
    }

    @Test
    void simulateFailure가_true이면_FAILED_상태를_200으로_반환한다() throws Exception {
        String body = """
                {"userId": 1, "templateId": "COUPON_ISSUED", "message": "쿠폰이 발급되었습니다."}
                """;

        MvcResult asyncResult = mockMvc.perform(post("/api/mock/notifications/kakao?simulateFailure=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void userId가_없으면_400을_반환한다() throws Exception {
        // Bean Validation 실패는 컨트롤러 메서드 진입 전에 걸려서 비동기로 안 넘어간다 — 바로 동기 400.
        String body = """
                {"templateId": "COUPON_ISSUED", "message": "쿠폰이 발급되었습니다."}
                """;

        mockMvc.perform(post("/api/mock/notifications/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void message가_빈문자열이면_400을_반환한다() throws Exception {
        String body = """
                {"userId": 1, "templateId": "COUPON_ISSUED", "message": ""}
                """;

        mockMvc.perform(post("/api/mock/notifications/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
