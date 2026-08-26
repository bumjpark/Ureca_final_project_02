package com.ureca.myureca.exception;

import java.time.LocalDateTime;

/**
 * 쿠폰 이벤트가 아직 오픈 전이거나 이미 종료된 경우 발생.
 *
 * <p>FR-10: 오픈 전 요청에는 오픈 예정 시각을 응답에 포함한다.
 */
public class CouponNotOpenedException extends RuntimeException {

    private final LocalDateTime openAt;

    /** 아직 오픈 전 */
    public CouponNotOpenedException(Long policyId, LocalDateTime openAt) {
        super("아직 오픈되지 않은 쿠폰입니다. policyId=" + policyId + ", openAt=" + openAt);
        this.openAt = openAt;
    }

    /** 이미 종료됨 */
    public CouponNotOpenedException(Long policyId) {
        super("이미 종료된 쿠폰입니다. policyId=" + policyId);
        this.openAt = null;
    }

    public LocalDateTime getOpenAt() {
        return openAt;
    }
}
