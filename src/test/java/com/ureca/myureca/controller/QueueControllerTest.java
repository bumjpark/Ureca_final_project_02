package com.ureca.myureca.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.domain.queue.QueueStatus;
import com.ureca.myureca.dto.request.QueueJoinRequest;
import com.ureca.myureca.dto.response.QueueJoinResponse;
import com.ureca.myureca.exception.CouponDuplicatedException;
import com.ureca.myureca.exception.CouponNotOpenedException;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.CouponSoldOutException;
import com.ureca.myureca.exception.QueueFullException;
import com.ureca.myureca.service.QueueService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(QueueController.class)
@AutoConfigureMockMvc
class QueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private QueueService queueService;

    @Test
    void 정상_대기열_등록시_200과_WAITING_상태를_반환한다() throws Exception {
        QueueJoinRequest request = new QueueJoinRequest(1L, 42L);
        QueueJoinResponse response = QueueJoinResponse.waiting(5L, 5L);
        when(queueService.joinQueue(any(QueueJoinRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.rank").value(5))
                .andExpect(jsonPath("$.activeToken").doesNotExist());
    }

    @Test
    void 즉시_입장_가능시_200과_ADMITTED_및_activeToken을_반환한다() throws Exception {
        QueueJoinRequest request = new QueueJoinRequest(1L, 42L);
        QueueJoinResponse response = QueueJoinResponse.admitted("testtoken123");
        when(queueService.joinQueue(any(QueueJoinRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.rank").value(0))
                .andExpect(jsonPath("$.activeToken").value("testtoken123"));
    }

    @Test
    void 필수_필드_누락시_400을_반환한다() throws Exception {
        String invalidJson = "{\"policyId\": 1}"; // userId 누락

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void policyId가_음수이면_400을_반환한다() throws Exception {
        String invalidJson = "{\"policyId\": -1, \"userId\": 42}";

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 이미_발급받은_유저는_409를_반환한다() throws Exception {
        QueueJoinRequest request = new QueueJoinRequest(1L, 42L);
        when(queueService.joinQueue(any(QueueJoinRequest.class)))
                .thenThrow(new CouponDuplicatedException("이미 발급받은 쿠폰입니다."));

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void 재고_없음_시_400을_반환한다() throws Exception {
        QueueJoinRequest request = new QueueJoinRequest(1L, 42L);
        when(queueService.joinQueue(any(QueueJoinRequest.class)))
                .thenThrow(new CouponSoldOutException("품절"));

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 대기열_정원_초과시_503을_반환한다() throws Exception {
        QueueJoinRequest request = new QueueJoinRequest(1L, 42L);
        when(queueService.joinQueue(any(QueueJoinRequest.class)))
                .thenThrow(new QueueFullException(1L));

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("대기열이 가득 찼습니다")));
    }

    @Test
    void 오픈_전_쿠폰_접근시_400과_openAt을_반환한다() throws Exception {
        QueueJoinRequest request = new QueueJoinRequest(1L, 42L);
        LocalDateTime futureOpenAt = LocalDateTime.now().plusHours(2);
        when(queueService.joinQueue(any(QueueJoinRequest.class)))
                .thenThrow(new CouponNotOpenedException(1L, futureOpenAt));

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("오픈되지 않은")));
    }

    @Test
    void 연타_호출_시_429_TOO_MANY_REQUESTS를_반환한다() throws Exception {
        QueueJoinRequest request = new QueueJoinRequest(1L, 42L);
        when(queueService.joinQueue(any(QueueJoinRequest.class)))
                .thenThrow(new com.ureca.myureca.exception.TooManyRequestsException());

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message", containsString("요청이 너무 빠릅니다")));
    }

    @Test
    void 존재하지_않는_정책은_404를_반환한다() throws Exception {
        QueueJoinRequest request = new QueueJoinRequest(999L, 42L);
        when(queueService.joinQueue(any(QueueJoinRequest.class)))
                .thenThrow(new CouponPolicyNotFoundException(999L));

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
