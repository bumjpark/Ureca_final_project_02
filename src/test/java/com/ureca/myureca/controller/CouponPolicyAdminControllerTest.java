package com.ureca.myureca.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.request.CouponPolicyUpdateRequest;
import com.ureca.myureca.dto.response.CouponPolicyResponse;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.InvalidCouponPolicyException;
import com.ureca.myureca.service.CouponPolicyService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(CouponPolicyAdminController.class)
@AutoConfigureMockMvc
class CouponPolicyAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CouponPolicyService couponPolicyService;

    @Test
    void 정책_목록_조회에_성공하면_200을_반환한다() throws Exception {
        CouponPolicyResponse response = new CouponPolicyResponse(
                1L, "테스트 쿠폰", CouponType.FIXED, 1000, 100, 0,
                LocalDateTime.now().plusDays(1), null, LocalDateTime.now(), LocalDateTime.now()
        );
        PageResponse<CouponPolicyResponse> pageResponse = PageResponse.from(
                new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1)
        );
        when(couponPolicyService.getCouponPolicies(any(Pageable.class))).thenReturn(pageResponse);

        mockMvc.perform(get("/api/admin/coupon-policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1L))
                .andExpect(jsonPath("$.content[0].title").value("테스트 쿠폰"));
    }

    @Test
    void 정책_단건_조회에_성공하면_200을_반환한다() throws Exception {
        CouponPolicyResponse response = new CouponPolicyResponse(
                1L, "테스트 쿠폰", CouponType.FIXED, 1000, 100, 0,
                LocalDateTime.now().plusDays(1), null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(couponPolicyService.getCouponPolicy(1L)).thenReturn(response);

        mockMvc.perform(get("/api/admin/coupon-policies/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.title").value("테스트 쿠폰"));
    }

    @Test
    void 정책_수정에_성공하면_200과_수정된_정보를_반환한다() throws Exception {
        LocalDateTime newOpenAt = LocalDateTime.now().plusDays(2);
        CouponPolicyUpdateRequest request = new CouponPolicyUpdateRequest(
                "수정된 쿠폰", CouponType.RATE, 20, 200, newOpenAt, null
        );
        CouponPolicyResponse response = new CouponPolicyResponse(
                1L, "수정된 쿠폰", CouponType.RATE, 20, 200, 0,
                newOpenAt, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(couponPolicyService.updateCouponPolicy(eq(1L), any(CouponPolicyUpdateRequest.class)))
                .thenReturn(response);

        mockMvc.perform(patch("/api/admin/coupon-policies/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.title").value("수정된 쿠폰"))
                .andExpect(jsonPath("$.couponType").value("RATE"))
                .andExpect(jsonPath("$.discountValue").value(20))
                .andExpect(jsonPath("$.totalQuantity").value(200));
    }

    @Test
    void 정책_수정시_필수값이_누락되면_400을_반환한다() throws Exception {
        String invalidJson = """
                {
                  "title": "",
                  "couponType": "FIXED",
                  "discountValue": 1000,
                  "totalQuantity": 100,
                  "openAt": "2099-01-01T00:00:00"
                }
                """;

        mockMvc.perform(patch("/api/admin/coupon-policies/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void 오픈_시각_이후_수정_시도시_400을_반환한다() throws Exception {
        LocalDateTime newOpenAt = LocalDateTime.now().plusDays(2);
        CouponPolicyUpdateRequest request = new CouponPolicyUpdateRequest(
                "수정 시도 쿠폰", CouponType.FIXED, 1000, 100, newOpenAt, null
        );
        when(couponPolicyService.updateCouponPolicy(eq(1L), any(CouponPolicyUpdateRequest.class)))
                .thenThrow(new InvalidCouponPolicyException("오픈 시각 이후에는 쿠폰 정책을 수정할 수 없습니다"));

        mockMvc.perform(patch("/api/admin/coupon-policies/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("오픈 시각 이후")));
    }

    @Test
    void 존재하지_않는_정책_수정시_404를_반환한다() throws Exception {
        LocalDateTime newOpenAt = LocalDateTime.now().plusDays(2);
        CouponPolicyUpdateRequest request = new CouponPolicyUpdateRequest(
                "수정 시도 쿠폰", CouponType.FIXED, 1000, 100, newOpenAt, null
        );
        when(couponPolicyService.updateCouponPolicy(eq(999L), any(CouponPolicyUpdateRequest.class)))
                .thenThrow(new CouponPolicyNotFoundException(999L));

        mockMvc.perform(patch("/api/admin/coupon-policies/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("존재하지 않는 쿠폰 정책입니다")));
    }
}
