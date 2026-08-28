package com.ureca.myureca.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.dto.response.CouponDetailResponse;
import com.ureca.myureca.dto.response.MaskedUserResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.exception.CouponNotOwnedException;
import com.ureca.myureca.service.MyCouponQueryService;

@WebMvcTest(CouponDetailController.class)
@AutoConfigureMockMvc
class CouponDetailControllerTest {

    private static final Long COUPON_ISSUE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String URL = "/api/coupons/" + COUPON_ISSUE_ID;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyCouponQueryService myCouponQueryService;

    @DisplayName("상세 조회 성공하면 200 과 마스킹된 소유자 정보를 준다")
    @Test
    void 상세조회_200() throws Exception {
        when(myCouponQueryService.getCouponDetail(eq(COUPON_ISSUE_ID), eq(USER_ID)))
                .thenReturn(detail());

        mockMvc.perform(get(URL).param("userId", String.valueOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponIssueId").value(1))
                .andExpect(jsonPath("$.receiptId").value("rcpt_abcdef0123456789"))
                .andExpect(jsonPath("$.user.name").value("홍*동"))
                .andExpect(jsonPath("$.user.email").value("pc*****@gmail.com"))
                .andExpect(jsonPath("$.discountLabel").value("5,000원 할인"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.displayStatus").value("ISSUED"))
                .andExpect(jsonPath("$.usable").value(true));

        // 경로 변수와 쿼리 파라미터가 뒤바뀌어 전달되면 여기서 걸린다
        verify(myCouponQueryService).getCouponDetail(COUPON_ISSUE_ID, USER_ID);
    }

    @DisplayName("userId 가 없으면 400 — 소유자 검증의 유일한 근거라 필수다")
    @Test
    void userId_누락_400() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("존재하지 않는 쿠폰이면 404")
    @Test
    void 없는_쿠폰_404() throws Exception {
        when(myCouponQueryService.getCouponDetail(eq(COUPON_ISSUE_ID), eq(USER_ID)))
                .thenThrow(new CouponIssueNotFoundException(COUPON_ISSUE_ID));

        mockMvc.perform(get(URL).param("userId", String.valueOf(USER_ID)))
                .andExpect(status().isNotFound());
    }

    @DisplayName("남의 쿠폰이면 403")
    @Test
    void 타인_쿠폰_403() throws Exception {
        when(myCouponQueryService.getCouponDetail(eq(COUPON_ISSUE_ID), eq(999L)))
                .thenThrow(new CouponNotOwnedException(COUPON_ISSUE_ID));

        mockMvc.perform(get(URL).param("userId", "999"))
                .andExpect(status().isForbidden());
    }

    private CouponDetailResponse detail() {
        LocalDateTime now = LocalDateTime.now();
        return new CouponDetailResponse(
                1L,
                "rcpt_abcdef0123456789",
                new MaskedUserResponse(10L, "홍*동", "pc*****@gmail.com"),
                100L,
                "신규가입 5000원 할인",
                CouponType.FIXED,
                5000,
                "5,000원 할인",
                IssueStatus.ISSUED,
                IssueStatus.ISSUED,
                true,
                now.minusDays(1),
                null,
                now.minusDays(2),
                now.plusDays(7));
    }
}
