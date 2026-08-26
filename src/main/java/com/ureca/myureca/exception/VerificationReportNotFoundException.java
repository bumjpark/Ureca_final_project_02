package com.ureca.myureca.exception;

public class VerificationReportNotFoundException extends RuntimeException {

    private final Long reportId;

    public VerificationReportNotFoundException(Long reportId) {
        super("존재하지 않는 검증 리포트입니다. reportId=" + reportId);
        this.reportId = reportId;
    }

    public Long getReportId() {
        return reportId;
    }
}
