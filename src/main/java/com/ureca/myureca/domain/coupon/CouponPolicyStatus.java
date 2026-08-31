package com.ureca.myureca.domain.coupon;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 정책의 생명주기 상태.
 */
@Getter
@RequiredArgsConstructor
public enum CouponPolicyStatus {

    /** 오픈 전 (오픈 시각 도달 전, 수정/삭제 가능) */
    BEFORE_OPEN("오픈전"),

    /** 오픈 (발급 진행 중) */
    OPEN("오픈"),

    /** 마감 (재고 소진 등으로 발급 중단) */
    CLOSED("마감"),

    /** 만료 (마감 기한 도달 또는 관리자에 의해 만료 처리됨) */
    EXPIRED("만료"),

    /** 삭제 (소프트 삭제됨) */
    DELETED("삭제");

    private final String description;
}
