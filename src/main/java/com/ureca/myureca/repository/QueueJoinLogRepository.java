package com.ureca.myureca.repository;

import com.ureca.myureca.domain.queue.QueueJoinLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 이슈 #12: 대기열 재입장(멱등 재조인) 시 같은 (policy, user)로 두 번째 호출이 들어올 수
     * 있다. {@code UNIQUE(coupon_policy_id, user_id)}를 JPA {@code save()}(존재 확인 없이
     * INSERT 시도)로 다루면 위반 시 예외 처리가 필요해지고, #11/#18과 같은 부류의
     * 트랜잭션 오염 위험을 또 만든다 — 애초에 순수 SQL {@code INSERT IGNORE}로 처리해
     * "최초 1건만 유지"를 DB 레벨에서 보장하고 예외 자체가 나지 않게 한다.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO queue_join_log "
            + "(coupon_policy_id, user_id, status, queue_rank, joined_at) "
            + "VALUES (:policyId, :userId, :status, :queueRank, :joinedAt)", nativeQuery = true)
    void insertIgnore(
            @Param("policyId") Long policyId,
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("queueRank") Long queueRank,
            @Param("joinedAt") LocalDateTime joinedAt
    );
}
