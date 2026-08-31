package com.ureca.myureca.service;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.dto.response.ReconciliationLogResponse;
import com.ureca.myureca.exception.ReconciliationBulkDispatchException;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** 재처리 이력 목록 조회. policyId/type/status 전부 선택 필터이며, 최신 로그가 먼저 오도록 정렬은 고정한다. */
    @Transactional(readOnly = true)
    public PageResponse<ReconciliationLogResponse> getReconciliationLogs(
            Long policyId, ReconciliationType type, ReconciliationStatus status, Pageable pageable
    ) {
        Page<ReconciliationLog> page = reconciliationLogRepository.search(policyId, type, status, pageable);
        return PageResponse.from(page.map(ReconciliationLogResponse::from));
    }

    public ReconciliationLogResponse retryOne(Long logId) {
        // 조회부터 변경까지 전부 ReconciliationRetryTrigger.dispatch() 안의 한 트랜잭션에서
        // 처리한다 — 여기서 미리 조회해 detached 엔티티를 넘기면 dispatch() 쪽 변경이 저장 안 된다.
        return reconciliationRetryTrigger.dispatch(logId);
    }

    /**
     * 한 트랜잭션으로 묶지 않는다(NOT_SUPPORTED) — 묶으면 중간에 예기치 못한 예외가 났을 때
     * 이미 처리된 행들의 retryCount 증가/markFailed까지 같이 롤백돼서, "이미 접수됨"이라고
     * 응답하는 것과 실제 DB 상태가 어긋난다. VerificationService.runVerification()과 동일 이유.
     *
     * @param type 전체 재처리 대상 타입. EVENT_REPUBLISH / DLT_REPROCESS / ISSUE_REPROCESS를
     *             지원한다(그 외 타입을 넘기면 대상이 0건이라 빈 리스트가 반환되거나, dispatch()
     *             단계에서 ReconciliationTypeNotSupportedException으로 걸러진다).
     *             <b>ISSUE_REPROCESS를 전체 재처리하면 해당 유저들에게 실제로 쿠폰이 발급된다</b> —
     *             REDIS_ONLY 드리프트는 "Redis가 맞고 DB가 틀렸다"고 단정할 수 없는 상태라
     *             (VerificationAsyncTrigger.registerRedisOnlyDrift 주석 참고) 자동 재시도 대상에서
     *             빠져 있고, 이 일괄 실행도 운영자가 목록을 눈으로 확인한 뒤 쓰는 것을 전제로 한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ReconciliationLogResponse> retryAll(ReconciliationType type) {
        List<Long> targetIds = reconciliationLogRepository
                .findByTypeAndStatusIn(type, RETRYABLE_STATUSES)
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
