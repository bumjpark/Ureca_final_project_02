package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponHistory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.EntityGraph;
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

    /**
     * 청크 단위 처리(CouponIssuedEventProcessor.processChunk)용 배치 인박스 체크.
     * 이벤트 하나마다 SELECT 한 번씩(existsByRequestId) 하는 대신, 청크 전체의 requestId를
     * 한 번의 IN 조회로 확인해 이미 처리된 것만 걸러낸다.
     */
    @Query("select h.requestId from CouponHistory h where h.requestId in :requestIds")
    Set<String> findExistingRequestIds(@Param("requestIds") Collection<String> requestIds);

    @Query("select new com.ureca.myureca.repository.CouponHistoryStatusSnapshot(h.couponIssue.id, h.newStatus) "
            + "from CouponHistory h where h.couponIssue.couponPolicy.id = :policyId "
            + "order by h.couponIssue.id asc, h.id asc")
    List<CouponHistoryStatusSnapshot> findStatusSnapshotsByCouponPolicyId(@Param("policyId") Long policyId);
    
    @EntityGraph(attributePaths = {"couponIssue", "couponIssue.user"})
    Optional<CouponHistory> findByRequestId(String requestId);
}
