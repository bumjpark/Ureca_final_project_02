package com.ureca.myureca.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.CouponPolicyCreateRequest;
import com.ureca.myureca.dto.CouponPolicyResponse;
import com.ureca.myureca.service.CouponPolicyService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(CouponPolicyController.class)
@AutoConfigureMockMvc
class CouponPolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CouponPolicyService couponPolicyService;

    @Test
    void 정책_생성에_성공하면_201과_Location_헤더를_반환한다() throws Exception {
        LocalDateTime openAt = LocalDateTime.now().plusDays(1);
        CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                "여름 휴가 쿠폰", CouponType.FIXED, 5000, 10000, openAt, null
        );
        CouponPolicy saved = new CouponPolicy(
                request.title(), request.couponType(), request.discountValue(),
                request.totalQuantity(), request.openAt(), request.closeAt()
        );
        CouponPolicyResponse response = CouponPolicyResponse.from(saved);
        // 저장된 것처럼 id를 부여한 새 응답 (record라 필드 재구성)
        CouponPolicyResponse responseWithId = new CouponPolicyResponse(
                1L, response.title(), response.couponType(), response.discountValue(),
                response.totalQuantity(), response.issuedQuantity(), response.openAt(),
                response.closeAt(), response.createdAt(), response.updatedAt()
        );
        when(couponPolicyService.createCouponPolicy(any(CouponPolicyCreateRequest.class)))
                .thenReturn(responseWithId);

        mockMvc.perform(post("/api/admin/coupon-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/admin/coupon-policies/1")))
                .andExpect(jsonPath("$.title").value("여름 휴가 쿠폰"))
                .andExpect(jsonPath("$.couponType").value("FIXED"))
                .andExpect(jsonPath("$.issuedQuantity").value(0));
    }

    @Test
    void 필수값이_없으면_400을_반환한다() throws Exception {
        String invalidJson = """
                {
                  "title": "",
                  "couponType": "FIXED",
                  "discountValue": 1000,
                  "totalQuantity": 100,
                  "openAt": "2020-01-01T00:00:00"
                }
                """;

        mockMvc.perform(post("/api/admin/coupon-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
