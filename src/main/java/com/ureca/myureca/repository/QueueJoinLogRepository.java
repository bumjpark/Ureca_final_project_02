package com.ureca.myureca.repository;

import com.ureca.myureca.domain.queue.QueueJoinLog;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueueJoinLogRepository extends JpaRepository<QueueJoinLog, Long> {

    /**
     * 선착순(FCFS) 검증 배치용
     */
    @Query("select q.userId from QueueJoinLog q where q.couponPolicyId = :policyId order by q.queueRank asc")
    List<Long> findUserIdsOrderByQueueRankAsc(@Param("policyId") Long policyId, Pageable pageable);

    /**
     * queue-join-events 컨슈머가 순번 구간 [1, rank]를 실제로 빠짐없이 적재했는지 확인용.
     */
    long countByCouponPolicyIdAndQueueRankLessThanEqual(Long policyId, Long rank);
}
