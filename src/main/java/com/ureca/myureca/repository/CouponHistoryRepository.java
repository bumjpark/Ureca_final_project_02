package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 정합성 검증 배치(Check B: 생명주기 불일치)용 및 쿠폰 이력 조회용 리포지토리.
 */
public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {

    List<CouponHistory> findByCouponIssueIdOrderByCreatedAtAsc(Long couponIssueId);

    @Query("select new com.ureca.myureca.repository.CouponHistoryStatusSnapshot(h.couponIssue.id, h.newStatus) "
            + "from CouponHistory h where h.couponIssue.couponPolicy.id = :policyId "
            + "order by h.couponIssue.id asc, h.id asc")
    List<CouponHistoryStatusSnapshot> findStatusSnapshotsByCouponPolicyId(@Param("policyId") Long policyId);
}
