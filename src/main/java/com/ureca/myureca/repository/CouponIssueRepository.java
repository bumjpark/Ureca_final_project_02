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
    // 그래프 창(seconds)만큼만 조회해 정책 전체 이력을 스캔하지 않는다.
    @Query(value = "select date_format(issued_at, '%Y-%m-%d %H:%i:%s') as bucket, count(*) as cnt "
            + "from coupon_issue where coupon_policy_id = :policyId and issued_at >= :since "
            + "group by bucket order by bucket", nativeQuery = true)
    List<Object[]> countIssuedBySecondSince(@Param("policyId") Long policyId, @Param("since") LocalDateTime since);

    // 가장 최근 발급 시각 — 그래프 창(seconds) 밖에서 마지막으로 발급된 경우(한동안 발급이 없던
    // 정책)에도 "마지막 발급이 언제였는지"를 보여주기 위해 창 크기와 무관하게 별도로 조회한다.
    // idx_policy_issued 인덱스 덕분에 정렬 없이 MAX()로 바로 찾는다.
    @Query("select max(ci.issuedAt) from CouponIssue ci where ci.couponPolicy.id = :policyId")
    LocalDateTime findMaxIssuedAtByCouponPolicyId(@Param("policyId") Long policyId);

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

//  ---------- 쿠폰 상태 변경 (사용 / 사용 취소 / 만료) ----------

    @Query("select ci from CouponIssue ci "
            + "join fetch ci.couponPolicy "
            + "join fetch ci.user "
            + "where ci.id = :id")
    Optional<CouponIssue> findDetailById(@Param("id") Long id);

    // ---- 발급 접수(receiptId) 상태 조회용(202 ACCEPTED 직후 폴링) ----
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
}
