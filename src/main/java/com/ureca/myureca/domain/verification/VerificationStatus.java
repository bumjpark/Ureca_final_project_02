package com.ureca.myureca.domain.verification;

/**
 * verification_report.status 값 도메인. DB CHECK 제약: chk_verification_status.
 * coupon_issue/coupon_history의 IssueStatus, reconciliation_log의 ReconciliationStatus와는
 * 값이 겹치더라도 별도 enum으로 관리한다.
 */
public enum VerificationStatus {
    SUCCESS,
    MISMATCH_FOUND
}
