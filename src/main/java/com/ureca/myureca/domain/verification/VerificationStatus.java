package com.ureca.myureca.domain.verification;

/**
 * verification_report.status 값 도메인. DB CHECK 제약: chk_verification_status (V2/V3 마이그레이션 참고).
 * coupon_issue/coupon_history의 IssueStatus, reconciliation_log의 ReconciliationStatus와는
 * 값이 겹치더라도 별도 enum으로 관리한다.
 */
public enum VerificationStatus {
    /** 비동기 실행 접수 직후, 백그라운드 처리가 끝나기 전까지의 임시 상태. */
    PENDING,
    SUCCESS,
    MISMATCH_FOUND,
    /** 백그라운드 실행 중 예외로 중단됨. PENDING에 영원히 갇히지 않도록 두는 종료 상태(V3). */
    FAILED
}
