package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import java.time.LocalDateTime;

/**
 * POST /api/admin/verification/run, GET /api/admin/verification/reports(/{id}) 공통 응답.
 */
public record VerificationReportResponse(
        Long id,
        Long policyId,
        LocalDateTime runAt,
        Integer totalIssued,
        Integer totalQuantity,
        Integer totalReserved,
        Integer mismatchCount,
        Integer oversoldCount,
        VerificationStatus status,
        String reportUrl,
        /** status가 FAILED일 때만 값이 있다. 그 외 상태에서는 null. */
        String failureReason,
        LocalDateTime createdAt
) {

    public static VerificationReportResponse from(VerificationReport report) {
        int totalQuantity = report.getCouponPolicy().getTotalQuantity();
        int oversoldCount = Math.max(0, report.getTotalIssued() - totalQuantity);
        return new VerificationReportResponse(
                report.getId(),
                report.getCouponPolicy().getId(),
                report.getRunAt(),
                report.getTotalIssued(),
                totalQuantity,
                report.getTotalReserved(),
                report.getMismatchCount(),
                oversoldCount,
                report.getStatus(),
                report.getReportUrl(),
                report.getFailureReason(),
                report.getCreatedAt()
        );
    }
}
