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

    Page<ReconciliationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReconciliationLog> findByTypeOrderByCreatedAtDesc(ReconciliationType type, Pageable pageable);

    Page<ReconciliationLog> findByStatusOrderByCreatedAtDesc(ReconciliationStatus status, Pageable pageable);

    Page<ReconciliationLog> findByTypeAndStatusOrderByCreatedAtDesc(
            ReconciliationType type, ReconciliationStatus status, Pageable pageable);

    /** DLT 소비 컨슈머의 인박스 체크(1차 방어)용. */
    boolean existsByEventKey(String eventKey);
}
