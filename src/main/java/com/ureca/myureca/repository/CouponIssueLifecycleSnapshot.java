package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.IssueStatus;
import java.time.LocalDateTime;

/**
 * 정합성 검증 배치(Check B: 생명주기 불일치)용 프로젝션.
 */
public record CouponIssueLifecycleSnapshot(Long issueId, Long userId, IssueStatus status, LocalDateTime usedAt) {
}
