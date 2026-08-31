package com.ureca.myureca.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.dto.response.ComponentHealthResponse;
import com.ureca.myureca.dto.response.HealthResponse;
import com.ureca.myureca.service.HealthCheckService;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthCheckService healthCheckService;

    @Test
    void 기본_호출은_인프라_점검_없이_즉시_UP을_반환한다() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // liveness 경로는 DB/Redis/Kafka를 절대 건드리면 안 된다 — 그게 이 분리의 핵심.
        verify(healthCheckService, never()).check();
    }

    @Test
    void deep_true_이고_모든_컴포넌트가_UP이면_200을_반환한다() throws Exception {
        Map<String, ComponentHealthResponse> components = Map.of(
                "mysql", ComponentHealthResponse.up(5),
                "redis", ComponentHealthResponse.up(3),
                "kafka", ComponentHealthResponse.up(10)
        );
        when(healthCheckService.check())
                .thenReturn(new HealthResponse("UP", LocalDateTime.now(), components));

        mockMvc.perform(get("/api/health").param("deep", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @Test
    void deep_true_이고_하나라도_DOWN이면_503을_반환한다() throws Exception {
        Map<String, ComponentHealthResponse> components = Map.of(
                "mysql", ComponentHealthResponse.up(5),
                "redis", ComponentHealthResponse.down(2000, "connection refused"),
                "kafka", ComponentHealthResponse.up(10)
        );
        when(healthCheckService.check())
                .thenReturn(new HealthResponse("DOWN", LocalDateTime.now(), components));

        mockMvc.perform(get("/api/health").param("deep", "true"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.redis.status").value("DOWN"));
    }
}
