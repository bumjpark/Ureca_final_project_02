package com.ureca.myureca.exception;

import com.ureca.myureca.domain.verification.VerificationStatus;

/**
 * 리포트 자체는 존재하지만 reportUrl이 null이라 CSV를 내려줄 수 없는 경우
 */
public class VerificationReportCsvNotAvailableException extends RuntimeException {

    private final Long reportId;
    private final VerificationStatus status;

    public VerificationReportCsvNotAvailableException(Long reportId, VerificationStatus status) {
        super(buildMessage(reportId, status));
        this.reportId = reportId;
        this.status = status;
    }

    private static String buildMessage(Long reportId, VerificationStatus status) {
        return switch (status) {
            case PENDING -> "검증이 아직 진행 중입니다. reportId=" + reportId + " (완료 후 다시 시도해주세요)";
            case SUCCESS -> "불일치가 없어 CSV가 생성되지 않았습니다. reportId=" + reportId;
            case FAILED -> "검증 실행이 실패해 CSV가 생성되지 않았습니다. reportId=" + reportId;
            case MISMATCH_FOUND -> "CSV 파일 정보가 누락되었습니다(내부 데이터 이상). reportId=" + reportId;
        };
    }

    public Long getReportId() {
        return reportId;
    }

    public VerificationStatus getStatus() {
        return status;
    }
}
