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

    /**
     * 인박스 패턴 1차 방어: Kafka Consumer 멱등성 체크용.
     * CouponIssuedEvent.receiptId()가 이미 처리됐는지 확인한다.
     * (receiptId → request_id 컬럼 매핑)
     */
    boolean existsByRequestId(String requestId);

    @Query("select new com.ureca.myureca.repository.CouponHistoryStatusSnapshot(h.couponIssue.id, h.newStatus) "
            + "from CouponHistory h where h.couponIssue.couponPolicy.id = :policyId "
            + "order by h.couponIssue.id asc, h.id asc")
    List<CouponHistoryStatusSnapshot> findStatusSnapshotsByCouponPolicyId(@Param("policyId") Long policyId);
}
