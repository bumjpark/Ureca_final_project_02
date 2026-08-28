package com.ureca.myureca.repository;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

    Page<ReconciliationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReconciliationLog> findByTypeOrderByCreatedAtDesc(ReconciliationType type, Pageable pageable);

    Page<ReconciliationLog> findByStatusOrderByCreatedAtDesc(ReconciliationStatus status, Pageable pageable);

    Page<ReconciliationLog> findByTypeAndStatusOrderByCreatedAtDesc(
            ReconciliationType type, ReconciliationStatus status, Pageable pageable);

    /** DLT 소비 컨슈머의 인박스 체크(1차 방어)용. */
    boolean existsByEventKey(String eventKey);
}
