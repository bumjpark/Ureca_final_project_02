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
     */
    List<ReconciliationLog> findByTypeAndStatusInOrderByCreatedAtAsc(
            ReconciliationType type, Collection<ReconciliationStatus> statuses, org.springframework.data.domain.Pageable pageable);

    Page<ReconciliationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReconciliationLog> findByTypeOrderByCreatedAtDesc(ReconciliationType type, Pageable pageable);

    Page<ReconciliationLog> findByStatusOrderByCreatedAtDesc(ReconciliationStatus status, Pageable pageable);

    Page<ReconciliationLog> findByTypeAndStatusOrderByCreatedAtDesc(
            ReconciliationType type, ReconciliationStatus status, Pageable pageable);

    /** DLT 소비 컨슈머의 인박스 체크(1차 방어)용. */
    boolean existsByEventKey(String eventKey);
}
