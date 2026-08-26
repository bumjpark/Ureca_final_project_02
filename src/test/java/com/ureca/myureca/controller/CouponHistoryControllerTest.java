package com.ureca.myureca.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.dto.response.CouponHistoryResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.service.CouponHistoryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CouponHistoryController.class)
class CouponHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponHistoryService couponHistoryService;

    @Test
    void 쿠폰_이력을_정상적으로_조회한다() throws Exception {
        Long couponIssueId = 1L;
        CouponHistoryResponse response1 = new CouponHistoryResponse(
                IssueStatus.ISSUED, IssueStatus.USED, "사용 완료", "req-1", LocalDateTime.of(2023, 1, 1, 10, 0));
        CouponHistoryResponse response2 = new CouponHistoryResponse(
                IssueStatus.USED, IssueStatus.ISSUED, "사용 취소", "req-2", LocalDateTime.of(2023, 1, 1, 10, 5));

        when(couponHistoryService.getCouponHistory(couponIssueId))
                .thenReturn(List.of(response1, response2));

        mockMvc.perform(get("/api/coupons/{couponIssueId}/history", couponIssueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].prevStatus").value("ISSUED"))
                .andExpect(jsonPath("$[0].newStatus").value("USED"))
                .andExpect(jsonPath("$[0].cancelReason").value("사용 완료"))
                .andExpect(jsonPath("$[0].requestId").value("req-1"))
                .andExpect(jsonPath("$[1].prevStatus").value("USED"))
                .andExpect(jsonPath("$[1].newStatus").value("ISSUED"))
                .andExpect(jsonPath("$[1].cancelReason").value("사용 취소"))
                .andExpect(jsonPath("$[1].requestId").value("req-2"));
    }

    @Test
    void 존재하지_않는_쿠폰발급건_조회시_404를_반환한다() throws Exception {
        Long couponIssueId = 999L;
        when(couponHistoryService.getCouponHistory(couponIssueId))
                .thenThrow(new CouponIssueNotFoundException(couponIssueId));

        mockMvc.perform(get("/api/coupons/{couponIssueId}/history", couponIssueId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 쿠폰 발급 건입니다. couponIssueId=" + couponIssueId));
    }

    @Test
    void 이력이_없는_경우_빈_리스트를_반환한다() throws Exception {
        Long couponIssueId = 2L;
        when(couponHistoryService.getCouponHistory(couponIssueId)).thenReturn(List.of());

        mockMvc.perform(get("/api/coupons/{couponIssueId}/history", couponIssueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
