package com.ureca.myureca.domain.coupon;

/**
 * coupon_history.prev_status 전용 열거형.
 *
 * <p>coupon_issue.status에서 사용하는 IssueStatus(ISSUED/USED/EXPIRED)와 혼용하지 않는다.
 * coupon_history는 상태 전이(Transition) 로그이므로, 최초 발급처럼 이전 상태가 없는 경우를
 * 표현하기 위해 NONE이 필요하다. IssueStatus에 NONE을 추가하면 coupon_issue.status에도
 * NONE이 허용되어 도메인 의미가 오염되므로 별도 enum으로 분리한다.
 *
 * <p>DB CHECK 제약(chk_history_status): prev_status IN ('NONE','ISSUED','USED','EXPIRED')
 */
public enum HistoryPrevStatus {
    /** 최초 발급 — 이전 상태가 없음을 의미 (coupon_history 전용) */
    NONE,
    /** 발급 완료 상태에서 전이 */
    ISSUED,
    /** 사용됨 상태에서 전이 */
    USED,
    /** 만료됨 상태에서 전이 */
    EXPIRED;

    /** IssueStatus → HistoryPrevStatus 변환. 상태 전이(사용/취소 등) 이력을 남길 때 사용한다. */
    public static HistoryPrevStatus from(IssueStatus status) {
        return HistoryPrevStatus.valueOf(status.name());
    }

    /**
     * HistoryPrevStatus → IssueStatus 역변환.
     * NONE(최초 발급 전용 값)은 대응하는 IssueStatus가 없어 예외를 던진다 — 호출부는
     * "상태 전이 이력"에서만 이 메서드를 써야 하며, 최초 발급 이력에는 쓰면 안 된다.
     */
    public IssueStatus toIssueStatus() {
        if (this == NONE) {
            throw new IllegalStateException("NONE은 IssueStatus로 변환할 수 없습니다 (최초 발급 이력 전용 값).");
        }
        return IssueStatus.valueOf(this.name());
    }
}
