package com.ureca.myureca.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.dto.request.QueueLimitUpdateRequest;
import com.ureca.myureca.dto.response.QueueLimitResponse;
import com.ureca.myureca.service.QueueLimitAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(QueueAdminController.class)
@AutoConfigureMockMvc
class QueueAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private QueueLimitAdminService queueLimitAdminService;

    @Test
    void 정상_대기열_Limit_수정시_200과_수정결과를_반환한다() throws Exception {
        QueueLimitUpdateRequest request = new QueueLimitUpdateRequest(1L, 500);
        QueueLimitResponse response = QueueLimitResponse.of(1L, 500);
        when(queueLimitAdminService.updateLimit(any(QueueLimitUpdateRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/admin/queue/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyId").value(1))
                .andExpect(jsonPath("$.limit").value(500))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void 글로벌_대기열_Limit_수정시_policyId가_null이어도_200을_반환한다() throws Exception {
        QueueLimitUpdateRequest request = new QueueLimitUpdateRequest(null, 600);
        QueueLimitResponse response = QueueLimitResponse.of(null, 600);
        when(queueLimitAdminService.updateLimit(any(QueueLimitUpdateRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/admin/queue/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyId").doesNotExist())
                .andExpect(jsonPath("$.limit").value(600));
    }

    @Test
    void limit이_0_이하이면_400_Bad_Request를_반환한다() throws Exception {
        String invalidJson = "{\"policyId\": 1, \"limit\": 0}";

        mockMvc.perform(patch("/api/admin/queue/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limit이_50000을_초과하면_400_Bad_Request를_반환한다() throws Exception {
        String invalidJson = "{\"policyId\": 1, \"limit\": 50001}";

        mockMvc.perform(patch("/api/admin/queue/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limit_필드가_누락되면_400_Bad_Request를_반환한다() throws Exception {
        String invalidJson = "{\"policyId\": 1}";

        mockMvc.perform(patch("/api/admin/queue/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}
