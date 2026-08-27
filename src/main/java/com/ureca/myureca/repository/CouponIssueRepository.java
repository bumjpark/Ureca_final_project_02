package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 내 쿠폰함 조회 전용 리포지토리.
 */
public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {


     // 정합성 검증 배치용
    @Query("select ci.user.id from CouponIssue ci where ci.couponPolicy.id = :policyId")
    List<Long> findUserIdsByCouponPolicyId(@Param("policyId") Long policyId);

    // 정합성 검증 배치(Check B: 생명주기 불일치)용 — 지연 연관관계까지 포함한 전체 엔티티를
    // 정책당 최대 수만 건 로드하지 않도록 상태 판단에 필요한 컬럼만 프로젝션한다.
    @Query("select new com.ureca.myureca.repository.CouponIssueLifecycleSnapshot("
            + "ci.id, ci.user.id, ci.status, ci.usedAt) "
            + "from CouponIssue ci where ci.couponPolicy.id = :policyId")
    List<CouponIssueLifecycleSnapshot> findLifecycleSnapshotsByCouponPolicyId(@Param("policyId") Long policyId);

    @EntityGraph(attributePaths = "couponPolicy")
    Page<CouponIssue> findByUserId(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "couponPolicy")
    Page<CouponIssue> findByUserIdAndStatus(Long userId, IssueStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "couponPolicy")
    Page<CouponIssue> findByUserIdAndCouponPolicyId(Long userId, Long couponPolicyId, Pageable pageable);

    @EntityGraph(attributePaths = "couponPolicy")
    Page<CouponIssue> findByUserIdAndCouponPolicyIdAndStatus(
            Long userId, Long couponPolicyId, IssueStatus status, Pageable pageable);

    // ---- Redis 재구성(완전 유실 복구, E) 전용 조회 ----
    // 유저 id 목록은 위의 findUserIdsByCouponPolicyId를 그대로 재사용한다
    // (coupon_policy_id, user_id 유니크 제약 덕분에 중복이 애초에 안 생겨서 distinct 불필요).
    // 발급 완료 건수는 별도 count 쿼리를 두지 않고 이 목록의 size()로 유도한다 —
    // count 쿼리와 목록 쿼리를 따로 날리면 그 사이 새 발급이 끼어들어 서로 다른 시점의
    // 스냅샷이 될 수 있기 때문에, 한 번의 조회 결과로만 두 값을 계산해 일관성을 보장한다.
}
