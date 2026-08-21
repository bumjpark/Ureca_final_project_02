package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 내 쿠폰함 조회 전용 리포지토리.
 */
public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    /**
     * 전체 조회. idx_issue_user(user_id) 로 좁힌 뒤 issued_at 정렬은 filesort 로 처리된다.
     */
    @EntityGraph(attributePaths = "couponPolicy")
    Page<CouponIssue> findByUserId(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "couponPolicy")
    Page<CouponIssue> findByUserIdAndStatus(Long userId, IssueStatus status, Pageable pageable);

    /**
     * 특정 캠페인 필터. 1인 1매 제한(FR-8) 검증의 핵심 조회로,
     * uk_policy_user(coupon_policy_id, user_id) UNIQUE 인덱스를 그대로 탄다.
     *
     * 제약이 살아 있는 한 결과는 항상 0건 또는 1건이다. 2건 이상이면 1인 1매가 깨진 것이다.
     */
    @EntityGraph(attributePaths = "couponPolicy")
    Page<CouponIssue> findByUserIdAndCouponPolicyId(Long userId, Long couponPolicyId, Pageable pageable);

    @EntityGraph(attributePaths = "couponPolicy")
    Page<CouponIssue> findByUserIdAndCouponPolicyIdAndStatus(
            Long userId, Long couponPolicyId, IssueStatus status, Pageable pageable);
}
