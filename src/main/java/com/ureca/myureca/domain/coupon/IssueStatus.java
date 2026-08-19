package com.ureca.myureca.domain.coupon;

/**
 * coupon_issue.status / coupon_history.prev_status / coupon_history.new_status 값 도메인.
 * DB CHECK 제약: chk_issue_status, chk_history_status.
 * 취소는 '사용 취소'만 존재하며 CANCELED 상태 없이 USED → ISSUED로 복귀한다.
 */
public enum IssueStatus {
    /** 발급 완료, 사용 가능 */
    ISSUED,
    /** 사용됨 */
    USED,
    /** 만료됨 */
    EXPIRED
}
