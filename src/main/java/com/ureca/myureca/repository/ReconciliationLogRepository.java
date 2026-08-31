package com.ureca.myureca.repository;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationLogRepository extends JpaRepository<ReconciliationLog, Long> {

    List<ReconciliationLog> findByTypeAndStatusIn(ReconciliationType type, Collection<ReconciliationStatus> statuses);

    /**
     * {@code ReconciliationAutoRetryScheduler}(이슈 #21) 전용 — 자동 재시도는 사람이 지켜보지
     * 않고 매 틱마다 도는 배치라, 전량 로드하는 위 메서드를 그대로 쓰면 장애가 길어질수록(PENDING이
     * 계속 쌓일수록) 한 틱에 통째로 재발행해 막 복구된 브로커/컨슈머를 다시 밀어버릴 수 있다.
     * {@code Pageable}로 틱당 처리량 상한을 두고, 오래 밀린 것부터(created_at ASC) 처리한다.
     * 사람이 명시적으로 호출하는 수동 재처리({@code ReconciliationService.retryAll}, 위 메서드
     * 사용)는 운영자가 규모를 감안해 트리거하는 동작이라 전량 로드를 그대로 유지한다.
     *
     * <p><b>{@code retryCount} 조건이 반드시 이 쿼리 안에 있어야 하는 이유</b>: 예전에는 이
     * 조건을 조회 후 자바 스트림에서 걸렀는데, 그러면 재시도를 다 소진한 행(retryCount >= 한도)이
     * PENDING/FAILED 상태 그대로 남으면서 {@code created_at ASC} 정렬의 선두를 영구히 차지한다.
     * 그런 행이 페이지 크기(500)만큼 쌓이는 순간 조회 결과가 전부 "이미 소진된 행"으로만 채워져,
     * 뒤에 들어온 정상 재처리 대상은 영원히 조회조차 되지 않고 자동 재처리가 조용히 완전 정지한다
     * (사람이 안 봐도 스스로 복구한다는 이 스케줄러의 존재 이유가 그대로 무력화됨). 조건을 DB로
     * 내리면 소진된 행은 애초에 페이지를 점유하지 않는다.
     */
    List<ReconciliationLog> findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
            ReconciliationType type,
            Collection<ReconciliationStatus> statuses,
            int retryCountLimit,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 재처리 이력 목록 조회 — policyId/type/status 전부 선택 필터(null이면 그 조건은 무시).
     * 예전에는 policyId 없이 type/status 조합별 파생 메서드 4개를 따로 두고 서비스에서
     * if/else로 골랐는데, 필터 차원이 하나(policyId) 늘면서 조합이 8가지로 늘어나 하나의
     * nullable-파라미터 쿼리로 통합했다.
     *
     * <p><b>{@code left join}을 명시하는 이유</b>(2026-08-31 버그 수정): 예전에는 where 절에서
     * {@code rl.couponIssue.couponPolicy.id}처럼 경로를 그대로 썼는데, JPQL의 암시적 조인은
     * {@code :policyId}가 null이어도 항상 <b>INNER JOIN</b>으로 생성된다. 그래서
     * {@code coupon_issue_id}가 null인 로그가 <b>필터와 무관하게 전부 결과에서 빠졌다</b> —
     * 재처리 화면이 어떤 필터를 걸어도 언제나 "대상 없음"으로 보였다(실측: DB에 8,282건이
     * 있는데 무필터 조회가 0건).
     *
     * <p>그리고 이건 예외적인 케이스가 아니다. 실제로 이 테이블에 쌓이는 주력 두 타입이 모두
     * {@code couponIssue}를 못 가진다 — {@code EVENT_REPUBLISH}는 발행 실패 시점에 아직 발급
     * 자체가 안 됐고({@code KafkaCouponEventProducer.recordPublishFailure}), REDIS_ONLY 드리프트로
     * 등록되는 {@code ISSUE_REPROCESS}도 "Redis엔 있는데 DB엔 없는" 건이라 마찬가지다.
     *
     * <p>다만 {@code policyId}를 <b>지정하면</b> 이 로그들은 여전히 빠진다(정책을 알 방법이
     * 없으므로). 정책 단위로 좁혀 보는 화면에서는 그 점을 감안해야 한다.
     */
    @Query("select rl from ReconciliationLog rl "
            + "left join rl.couponIssue ci "
            + "left join ci.couponPolicy cp "
            + "where (:policyId is null or cp.id = :policyId) "
            + "and (:type is null or rl.type = :type) "
            + "and (:status is null or rl.status = :status) "
            + "order by rl.createdAt desc")
    Page<ReconciliationLog> search(
            @Param("policyId") Long policyId,
            @Param("type") ReconciliationType type,
            @Param("status") ReconciliationStatus status,
            Pageable pageable);

    /** DLT 소비 컨슈머의 인박스 체크(1차 방어)용. */
    boolean existsByEventKey(String eventKey);

    /**
     * 드리프트 등록({@code VerificationAsyncTrigger.registerRedisOnlyDrift})용 배치 인박스 체크.
     * 건마다 {@link #existsByEventKey}를 호출하면 미아 예약이 1만 건 쌓인 상태에서 SELECT만
     * 1만 번 나가면서 스케줄러 스레드를 수 분간 붙잡는다(2026-08-31 실측: 11,000건 등록에 2분).
     * {@code CouponHistoryRepository.findExistingRequestIds}와 같은 방식으로 한 번의 IN 조회로 묶는다.
     */
    @Query("select rl.eventKey from ReconciliationLog rl where rl.eventKey in :eventKeys")
    Set<String> findExistingEventKeys(@Param("eventKeys") Collection<String> eventKeys);

    /** 발급 접수(receiptId) 상태 조회용 — receiptId를 eventKey로 쓰는 EVENT_REPUBLISH 건이
     *  있는지 확인해, "아직 처리 중"과 "재처리가 필요한 실패"를 구분한다. */
    Optional<ReconciliationLog> findByEventKey(String eventKey);
}
