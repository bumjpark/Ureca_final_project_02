package com.ureca.myureca.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;

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
    // 예전엔 Kafka 이벤트를 직접 재처리하는 로직도 여기서 중복 방지용 조회를 썼지만,
    // 상시 Consumer가 그 역할을 전담하는 쪽으로 재설계되면서 더 이상 필요 없어져 제거했다.

    /** 정책별 발급 완료 건수. Redis stock 재계산(totalQuantity - 이 값)에 사용. */
    long countByCouponPolicyId(Long couponPolicyId);
    
//  ---------- 쿠폰 상태 변경 (사용 / 사용 취소 / 만료) ----------

    @Query("select ci from CouponIssue ci "
            + "join fetch ci.couponPolicy "
            + "join fetch ci.user "
            + "where ci.id = :id")
    Optional<CouponIssue> findDetailById(@Param("id") Long id);

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
