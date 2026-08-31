package com.ureca.myureca.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.dto.response.KakaoNotificationResponse;
import com.ureca.myureca.service.MockNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 발송 로직 자체(지연 시뮬레이션 + 성공/실패 판정 + 로그 저장)는 {@link MockNotificationService}로
 * 옮겨졌으므로, 여기서는 그 서비스를 Mockito로 대체하고 컨트롤러의 라우팅/검증/응답 매핑만 검증한다.
 * 단건 발송(/kakao)은 여전히 {@code WebAsyncTask}를 반환하므로 asyncStarted() → asyncDispatch()
 * 2단계 검증이 필요하다 — 일괄 발송(/kakao/bulk)은 동기 응답이라 그대로 perform()만 하면 된다.
 */
@WebMvcTest(MockNotificationController.class)
@AutoConfigureMockMvc
class MockNotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MockNotificationService mockNotificationService;

    @Test
    void 정상_요청이면_SENT_상태를_반환한다() throws Exception {
        when(mockNotificationService.send(any(), anyBoolean()))
                .thenReturn(KakaoNotificationResponse.sent("mock-msg-1"));

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
        when(mockNotificationService.send(any(), anyBoolean()))
                .thenReturn(KakaoNotificationResponse.failed("mock-msg-2"));

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

    @Test
    void 일괄발송_요청이_유효하면_작업id와_대상자수와_함께_202를_반환한다() throws Exception {
        when(mockNotificationService.sendBulkByPolicy(1L, "COUPON_ISSUED", "메시지"))
                .thenReturn(new MockNotificationService.BulkSendResult(7L, 3));

        String body = """
                {"policyId": 1, "templateId": "COUPON_ISSUED", "message": "메시지"}
                """;

        mockMvc.perform(post("/api/mock/notifications/kakao/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(7))
                .andExpect(jsonPath("$.policyId").value(1))
                .andExpect(jsonPath("$.targetCount").value(3));
    }
}
