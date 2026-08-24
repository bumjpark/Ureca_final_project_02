package com.ureca.myureca.controller;

import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.dto.response.VerificationReportResponse;
import com.ureca.myureca.service.VerificationService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/verification")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService verificationService;

    /**
     * 정합성 검증 배치 수동 실행 (비동기).
     */
    @PostMapping("/run")
    public ResponseEntity<List<VerificationReportResponse>> run(
            @RequestParam(required = false) Long policyId
    ) {
        List<VerificationReportResponse> responses = verificationService.runVerification(policyId);
        return ResponseEntity.accepted().body(responses);
    }

    /**
     * 검증 리포트 목록 조회. policyId/status는 둘 다 선택 필터.
     */
    @GetMapping("/reports")
    public PageResponse<VerificationReportResponse> getReports(
            @RequestParam(required = false) Long policyId,
            @RequestParam(required = false) VerificationStatus status,
            @PageableDefault(size = 10) Pageable pageable
    ) {
        return verificationService.getVerificationReports(policyId, status, pageable);
    }

    /**
     * 검증 리포트 상세/다운로드
     */
    @GetMapping("/reports/{id}")
    public ResponseEntity<?> getReport(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "json") String format
    ) {
        if ("csv".equalsIgnoreCase(format)) {
            return downloadCsv(id);
        }
        return ResponseEntity.ok(verificationService.getVerificationReport(id));
    }

    private ResponseEntity<Resource> downloadCsv(Long id) {
        VerificationService.ReportCsvFile file = verificationService.getVerificationReportCsv(id);
        MediaType csv = new MediaType("text", "csv", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(csv)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(file.resource());
    }
}
