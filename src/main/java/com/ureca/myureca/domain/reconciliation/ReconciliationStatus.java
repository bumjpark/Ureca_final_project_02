package com.ureca.myureca.domain.reconciliation;

/**
 * reconciliation_log.status 값 도메인. DB CHECK 제약: chk_reconciliation_status.
 * coupon_issue/coupon_history의 IssueStatus, verification_report의 VerificationStatus와는
 * 값이 겹치더라도 별도 enum으로 관리한다.
 */
public enum ReconciliationStatus {
    PENDING,
    SUCCESS,
    FAILED
}
