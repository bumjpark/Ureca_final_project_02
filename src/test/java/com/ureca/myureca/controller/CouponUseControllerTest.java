package com.ureca.myureca.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.dto.request.CouponUseRequest;
import com.ureca.myureca.dto.response.CouponUseResponse;
import com.ureca.myureca.exception.CouponNotOwnedException;
import com.ureca.myureca.exception.CouponStatusConflictException;
import com.ureca.myureca.service.CouponUseService;

import tools.jackson.databind.ObjectMapper;

/**
 * 서비스가 던진 예외가 실제로 어떤 HTTP 상태 코드로 나가는지 확인한다.
 * 서비스 단위 테스트는 예외 타입까지만 보증하므로, 매핑은 이 계층에서만 검증된다.
 */
@WebMvcTest(CouponUseController.class)
@AutoConfigureMockMvc
class CouponUseControllerTest {

    private static final String URL = "/api/coupons/1/use";
    private static final String KEY = "use-20260826-abc123";
    private static final Long OWNER_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CouponUseService couponUseService;

    @DisplayName("사용 취소에 성공하면 200 과 함께 usedAt 이 null 로 내려간다")
    @Test
    void 사용취소_200() throws Exception {
        when(couponUseService.changeStatus(anyLong(), anyString(), any(CouponUseRequest.class)))
                .thenReturn(CouponUseResponse.applied(
                        1L, "rcpt_abcdef0123456789", IssueStatus.USED, IssueStatus.ISSUED, null));

        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CouponUseRequest(OWNER_ID, IssueStatus.ISSUED, "주문 취소"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevStatus").value("USED"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.usedAt").isEmpty())
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @DisplayName("Idempotency-Key 헤더가 없으면 400")
    @Test
    void 멱등키_헤더_누락_400() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CouponUseRequest(OWNER_ID, IssueStatus.USED, null))))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("userId 가 없으면 400 — 소유자 검증의 유일한 근거라 필수다")
    @Test
    void userId_누락_400() throws Exception {
        String json = """
                { "status": "USED" }
                """;

        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("남의 쿠폰이면 403")
    @Test
    void 타인_쿠폰_403() throws Exception {
        when(couponUseService.changeStatus(anyLong(), anyString(), any(CouponUseRequest.class)))
                .thenThrow(new CouponNotOwnedException(1L));

        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CouponUseRequest(999L, IssueStatus.USED, null))))
                .andExpect(status().isForbidden());
    }

    @DisplayName("멱등키를 다른 요청에 재사용하면 409")
    @Test
    void 멱등키_재사용_409() throws Exception {
        when(couponUseService.changeStatus(anyLong(), anyString(), any(CouponUseRequest.class)))
                .thenThrow(new CouponStatusConflictException(
                        "이미 다른 요청에 사용된 Idempotency-Key 입니다. 새 키로 요청해주세요."));

        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CouponUseRequest(OWNER_ID, IssueStatus.ISSUED, "주문 취소"))))
                .andExpect(status().isConflict());
    }
}
