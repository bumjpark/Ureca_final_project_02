package com.ureca.myureca.repository;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationLogRepository extends JpaRepository<ReconciliationLog, Long> {

    List<ReconciliationLog> findByTypeAndStatusIn(ReconciliationType type, Collection<ReconciliationStatus> statuses);
}
