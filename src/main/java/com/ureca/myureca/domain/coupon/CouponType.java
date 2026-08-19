package com.ureca.myureca.domain.coupon;

/**
 * coupon_policy.coupon_type 값 도메인.
 * DDL 상 DB CHECK 제약(chk_*)은 없지만 값이 RATE/FIXED로 고정되어 있어 enum으로 관리한다.
 */
public enum CouponType {
    /** 정률 할인 */
    RATE,
    /** 정액 할인 */
    FIXED
}
