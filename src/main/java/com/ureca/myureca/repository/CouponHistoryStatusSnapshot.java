package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.IssueStatus;

/**
 * 정합성 검증 배치(Check B: 생명주기 불일치)용 프로젝션.
 */
public record CouponHistoryStatusSnapshot(Long issueId, IssueStatus newStatus) {
}
