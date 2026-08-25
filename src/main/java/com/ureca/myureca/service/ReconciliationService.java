package com.ureca.myureca.service;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.response.ReconciliationLogResponse;
import com.ureca.myureca.exception.ReconciliationBulkDispatchException;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정합성 복구(수동 재처리) 오케스트레이터
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private static final List<ReconciliationStatus> RETRYABLE_STATUSES =
            List.of(ReconciliationStatus.PENDING, ReconciliationStatus.FAILED);

    private final ReconciliationLogRepository reconciliationLogRepository;
    private final ReconciliationRetryTrigger reconciliationRetryTrigger;

    public ReconciliationLogResponse retryOne(Long logId) {
        // 조회부터 변경까지 전부 ReconciliationRetryTrigger.dispatch() 안의 한 트랜잭션에서
        // 처리한다 — 여기서 미리 조회해 detached 엔티티를 넘기면 dispatch() 쪽 변경이 저장 안 된다.
        return reconciliationRetryTrigger.dispatch(logId);
    }

    /**
     * 한 트랜잭션으로 묶지 않는다(NOT_SUPPORTED) — 묶으면 중간에 예기치 못한 예외가 났을 때
     * 이미 처리된 행들의 retryCount 증가/markFailed까지 같이 롤백돼서, "이미 접수됨"이라고
     * 응답하는 것과 실제 DB 상태가 어긋난다. VerificationService.runVerification()과 동일 이유.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ReconciliationLogResponse> retryAll() {
        List<Long> targetIds = reconciliationLogRepository
                .findByTypeAndStatusIn(ReconciliationType.EVENT_REPUBLISH, RETRYABLE_STATUSES)
                .stream()
                .map(ReconciliationLog::getId)
                .toList();

        List<ReconciliationLogResponse> dispatched = new ArrayList<>();
        for (Long logId : targetIds) {
            try {
                dispatched.add(reconciliationRetryTrigger.dispatch(logId));
            } catch (RuntimeException e) {
                List<Long> dispatchedIds = dispatched.stream().map(ReconciliationLogResponse::id).toList();
                throw new ReconciliationBulkDispatchException(
                        "로그 id=" + logId + " 재처리 접수 중 실패했습니다. "
                                + "이미 접수된 로그(재실행 불필요): " + dispatchedIds,
                        dispatchedIds,
                        e
                );
            }
        }
        return dispatched;
    }
}
