package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
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

/**
 * 내 쿠폰함 조회 및 상태 관리 리포지토리.
 */
public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    // 정합성 검증 배치용
    @Query("select ci.user.id from CouponIssue ci where ci.couponPolicy.id = :policyId")
    List<Long> findUserIdsByCouponPolicyId(@Param("policyId") Long policyId);

    // ---- 발급 현황 페이지 — 실시간 그래프/보조 지표용 ----

    // 상태(ISSUED/USED/EXPIRED)별 건수 — 발급 현황 페이지의 "사용/만료" 지표
    @Query("select ci.status, count(ci) from CouponIssue ci "
            + "where ci.couponPolicy.id = :policyId group by ci.status")
    List<Object[]> countByStatusGroupedForPolicy(@Param("policyId") Long policyId);

    // 최근 1초 이내 발급 건수 — "초당 발급 속도" 지표
    @Query("select count(ci) from CouponIssue ci "
            + "where ci.couponPolicy.id = :policyId and ci.issuedAt >= :since")
    long countIssuedSince(@Param("policyId") Long policyId, @Param("since") LocalDateTime since);

    // 1초 단위 버킷으로 그룹핑한 발급 건수 — idx_policy_issued(coupon_policy_id, issued_at) 인덱스를 탄다.
    @Query(value = "select date_format(issued_at, '%Y-%m-%d %H:%i:%s') as bucket, count(*) as cnt "
            + "from coupon_issue where coupon_policy_id = :policyId and issued_at >= :since "
            + "group by bucket order by bucket", nativeQuery = true)
    List<Object[]> countIssuedBySecondSince(@Param("policyId") Long policyId, @Param("since") LocalDateTime since);

    // 가장 최근 발급 시각
    @Query("select max(ci.issuedAt) from CouponIssue ci where ci.couponPolicy.id = :policyId")
    LocalDateTime findMaxIssuedAtByCouponPolicyId(@Param("policyId") Long policyId);

    // 정합성 검증 배치용 프로젝션
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

    // ---- 논리적 만료 상태를 반영한 필터 쿼리 ----
    @Query(value = "SELECT ci FROM CouponIssue ci JOIN FETCH ci.couponPolicy cp "
            + "WHERE ci.user.id = :userId "
            + "AND (ci.status = 'EXPIRED' "
            + "     OR (ci.status = 'ISSUED' AND cp.closeAt IS NOT NULL AND cp.closeAt < :now))",
           countQuery = "SELECT COUNT(ci) FROM CouponIssue ci JOIN ci.couponPolicy cp "
            + "WHERE ci.user.id = :userId "
            + "AND (ci.status = 'EXPIRED' "
            + "     OR (ci.status = 'ISSUED' AND cp.closeAt IS NOT NULL AND cp.closeAt < :now))")
    Page<CouponIssue> findByUserIdAndEffectiveStatusExpired(
            @Param("userId") Long userId, @Param("now") LocalDateTime now, Pageable pageable);

    @Query(value = "SELECT ci FROM CouponIssue ci JOIN FETCH ci.couponPolicy cp "
            + "WHERE ci.user.id = :userId "
            + "AND ci.status = 'ISSUED' "
            + "AND (cp.closeAt IS NULL OR cp.closeAt >= :now)",
           countQuery = "SELECT COUNT(ci) FROM CouponIssue ci JOIN ci.couponPolicy cp "
            + "WHERE ci.user.id = :userId "
            + "AND ci.status = 'ISSUED' "
            + "AND (cp.closeAt IS NULL OR cp.closeAt >= :now)")
    Page<CouponIssue> findByUserIdAndEffectiveStatusIssued(
            @Param("userId") Long userId, @Param("now") LocalDateTime now, Pageable pageable);

    @Query(value = "SELECT ci FROM CouponIssue ci JOIN FETCH ci.couponPolicy cp "
            + "WHERE ci.user.id = :userId AND cp.id = :policyId "
            + "AND (ci.status = 'EXPIRED' "
            + "     OR (ci.status = 'ISSUED' AND cp.closeAt IS NOT NULL AND cp.closeAt < :now))",
           countQuery = "SELECT COUNT(ci) FROM CouponIssue ci JOIN ci.couponPolicy cp "
            + "WHERE ci.user.id = :userId AND cp.id = :policyId "
            + "AND (ci.status = 'EXPIRED' "
            + "     OR (ci.status = 'ISSUED' AND cp.closeAt IS NOT NULL AND cp.closeAt < :now))")
    Page<CouponIssue> findByUserIdAndCouponPolicyIdAndEffectiveStatusExpired(
            @Param("userId") Long userId, @Param("policyId") Long policyId,
            @Param("now") LocalDateTime now, Pageable pageable);

    @Query(value = "SELECT ci FROM CouponIssue ci JOIN FETCH ci.couponPolicy cp "
            + "WHERE ci.user.id = :userId AND cp.id = :policyId "
            + "AND ci.status = 'ISSUED' "
            + "AND (cp.closeAt IS NULL OR cp.closeAt >= :now)",
           countQuery = "SELECT COUNT(ci) FROM CouponIssue ci JOIN ci.couponPolicy cp "
            + "WHERE ci.user.id = :userId AND cp.id = :policyId "
            + "AND ci.status = 'ISSUED' "
            + "AND (cp.closeAt IS NULL OR cp.closeAt >= :now)")
    Page<CouponIssue> findByUserIdAndCouponPolicyIdAndEffectiveStatusIssued(
            @Param("userId") Long userId, @Param("policyId") Long policyId,
            @Param("now") LocalDateTime now, Pageable pageable);

//  ---------- 쿠폰 상태 변경 (사용 / 사용 취소 / 만료) ----------

    @Query("select ci from CouponIssue ci "
            + "join fetch ci.couponPolicy "
            + "join fetch ci.user "
            + "where ci.id = :id")
    Optional<CouponIssue> findDetailById(@Param("id") Long id);

    @Query("select ci from CouponIssue ci "
            + "join fetch ci.couponPolicy "
            + "join fetch ci.user "
            + "where ci.receiptId = :receiptId")
    Optional<CouponIssue> findByReceiptId(@Param("receiptId") String receiptId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CouponIssue ci "
            + "set ci.status = :newStatus, ci.usedAt = :usedAt, ci.updatedAt = :now "
            + "where ci.id = :id and ci.status = :expectedStatus")
    int updateStatusIf(@Param("id") Long id,
                       @Param("expectedStatus") IssueStatus expectedStatus,
                       @Param("newStatus") IssueStatus newStatus,
                       @Param("usedAt") LocalDateTime usedAt,
                       @Param("now") LocalDateTime now);

    // ---- 대용량 청크(Chunk) 분할 만료 쿼리 ----
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE coupon_issue "
            + "SET status = 'EXPIRED', updated_at = :now "
            + "WHERE coupon_policy_id = :policyId AND status = 'ISSUED' "
            + "LIMIT :chunkSize", nativeQuery = true)
    int bulkExpireChunkByPolicyId(@Param("policyId") Long policyId,
                                  @Param("now") LocalDateTime now,
                                  @Param("chunkSize") int chunkSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CouponIssue ci SET ci.status = 'EXPIRED', ci.updatedAt = :now "
            + "WHERE ci.couponPolicy.id = :policyId AND ci.status = 'ISSUED'")
    int bulkExpireByPolicyId(@Param("policyId") Long policyId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CouponIssue ci SET ci.status = 'EXPIRED', ci.updatedAt = :now "
            + "WHERE ci.status = 'ISSUED' AND ci.couponPolicy.id IN "
            + "(SELECT cp.id FROM CouponPolicy cp WHERE cp.closeAt IS NOT NULL AND cp.closeAt < :now AND cp.deletedAt IS NULL)")
    int bulkExpireAllExpired(@Param("now") LocalDateTime now);
}
