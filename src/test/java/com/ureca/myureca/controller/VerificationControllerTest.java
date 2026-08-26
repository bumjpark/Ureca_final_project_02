package com.ureca.myureca.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.dto.response.VerificationReportResponse;
import com.ureca.myureca.exception.VerificationReportCsvNotAvailableException;
import com.ureca.myureca.exception.VerificationReportFileMissingException;
import com.ureca.myureca.exception.VerificationReportNotFoundException;
import com.ureca.myureca.service.VerificationService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VerificationController.class)
@AutoConfigureMockMvc
class VerificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationService verificationService;

    @Test
    void format_지정없이_조회하면_JSON_상세를_반환한다() throws Exception {
        VerificationReportResponse response = new VerificationReportResponse(
                100L, 1L, LocalDateTime.now(), 3, 10, 0, 0, 0,
                VerificationStatus.SUCCESS, null, null, LocalDateTime.now()
        );
        when(verificationService.getVerificationReport(100L)).thenReturn(response);

        mockMvc.perform(get("/api/admin/verification/reports/100"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void format이_csv면_CSV_파일을_다운로드_응답으로_반환한다() throws Exception {
        byte[] bytes = ("policyId,userId,couponIssueId,discrepancyType,detectedAt\n"
                + "1,2,,REDIS_ONLY,2026-08-24T00:00:00\n").getBytes(StandardCharsets.UTF_8);
        VerificationService.ReportCsvFile file = new VerificationService.ReportCsvFile(
                new ByteArrayResource(bytes), "verification-report-1-100.csv"
        );
        when(verificationService.getVerificationReportCsv(100L)).thenReturn(file);

        mockMvc.perform(get("/api/admin/verification/reports/100").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("verification-report-1-100.csv")))
                .andExpect(content().contentTypeCompatibleWith(new MediaType("text", "csv")))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void 존재하지_않는_리포트_id면_404를_반환한다() throws Exception {
        when(verificationService.getVerificationReport(999L))
                .thenThrow(new VerificationReportNotFoundException(999L));

        mockMvc.perform(get("/api/admin/verification/reports/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void CSV가_아직_준비되지_않았으면_409를_반환한다() throws Exception {
        when(verificationService.getVerificationReportCsv(100L))
                .thenThrow(new VerificationReportCsvNotAvailableException(100L, VerificationStatus.PENDING));

        mockMvc.perform(get("/api/admin/verification/reports/100").param("format", "csv"))
                .andExpect(status().isConflict());
    }

    @Test
    void 저장된_CSV_파일이_디스크에_없으면_410을_반환한다() throws Exception {
        when(verificationService.getVerificationReportCsv(100L))
                .thenThrow(new VerificationReportFileMissingException(100L, "reports/x.csv"));

        mockMvc.perform(get("/api/admin/verification/reports/100").param("format", "csv"))
                .andExpect(status().isGone());
    }
}
