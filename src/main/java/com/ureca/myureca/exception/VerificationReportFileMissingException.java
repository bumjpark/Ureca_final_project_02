package com.ureca.myureca.exception;

/**
 * DB엔 reportUrl이 저장돼 있는데(서버가 CSV를 만들었는데) 실제 디스크 파일을 찾을 수 없는 경우
 */
public class VerificationReportFileMissingException extends RuntimeException {

    private final Long reportId;
    private final String reportUrl;

    public VerificationReportFileMissingException(Long reportId, String reportUrl) {
        super("CSV 파일을 디스크에서 찾을 수 없습니다. reportId=" + reportId + ", reportUrl=" + reportUrl);
        this.reportId = reportId;
        this.reportUrl = reportUrl;
    }

    public Long getReportId() {
        return reportId;
    }

    public String getReportUrl() {
        return reportUrl;
    }
}
