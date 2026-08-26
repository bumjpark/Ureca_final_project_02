package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 상태 변경 대상 단건 조회. 소유자 검증(user)과 유효기간 검증(couponPolicy.closeAt)에
     * 둘 다 필요하므로 한 번에 가져온다.
     */
    @Query("select ci from CouponIssue ci "
            + "join fetch ci.couponPolicy "
            + "join fetch ci.user "
            + "where ci.id = :id")
    Optional<CouponIssue> findDetailById(@Param("id") Long id);

    /**
     * 조건부 UPDATE — 상태 전이의 원자성을 보증하는 유일한 지점 (README 2-3 확정 방침).
     *
     * <p>{@code where ... and ci.status = :expectedStatus} 덕분에 동시 요청이 몰려도
     * 실제로 상태를 바꾸는 쪽은 정확히 하나뿐이다. 나머지는 갱신 건수 0을 받는다.
     * 락도, 낙관적 버전 컬럼도 필요 없다.
     *
     * <p>{@code @UpdateTimestamp} 는 벌크 UPDATE 에서 동작하지 않으므로 updatedAt 을 직접 넣는다.
     *
     * @param usedAt 사용 처리면 현재 시각, 사용 취소·만료면 null
     * @return 실제로 갱신된 행 수. 0이면 그 사이 다른 요청이 상태를 바꾼 것이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CouponIssue ci "
            + "set ci.status = :newStatus, ci.usedAt = :usedAt, ci.updatedAt = :now "
            + "where ci.id = :id and ci.status = :expectedStatus")
    int updateStatusIf(@Param("id") Long id,
                       @Param("expectedStatus") IssueStatus expectedStatus,
                       @Param("newStatus") IssueStatus newStatus,
                       @Param("usedAt") LocalDateTime usedAt,
                       @Param("now") LocalDateTime now);
}
