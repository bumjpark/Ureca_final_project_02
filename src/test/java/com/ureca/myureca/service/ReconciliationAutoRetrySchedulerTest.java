package com.ureca.myureca.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.response.ReconciliationLogResponse;
import com.ureca.myureca.exception.ReconciliationAlreadySucceededException;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReconciliationAutoRetrySchedulerTest {

    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

    @Mock
    private ReconciliationRetryTrigger reconciliationRetryTrigger;

    @InjectMocks
    private ReconciliationAutoRetryScheduler scheduler;

    private ReconciliationLog logWithRetryCount(Long id, ReconciliationType type, int retryCount) {
        ReconciliationLog log = new ReconciliationLog(type, "event-key-" + id, null, "coupon-issued-events",
                "{\"policyId\":1,\"userId\":100,\"receiptId\":\"rcpt_1\",\"issuedAt\":\"2026-08-24T00:00:00\"}", null);
        ReflectionTestUtils.setField(log, "id", id);
        ReflectionTestUtils.setField(log, "retryCount", retryCount);
        return log;
    }

    @Test
    void EVENT_REPUBLISH와_DLT_REPROCESS_둘_다_대상으로_조회한다() {
        when(reconciliationLogRepository.findByTypeAndStatusIn(eq(ReconciliationType.EVENT_REPUBLISH), any()))
                .thenReturn(List.of());
        when(reconciliationLogRepository.findByTypeAndStatusIn(eq(ReconciliationType.DLT_REPROCESS), any()))
                .thenReturn(List.of());

        scheduler.retryPendingAndFailed();

        verify(reconciliationLogRepository).findByTypeAndStatusIn(eq(ReconciliationType.EVENT_REPUBLISH), any());
        verify(reconciliationLogRepository).findByTypeAndStatusIn(eq(ReconciliationType.DLT_REPROCESS), any());
    }

    @Test
    void PENDING_FAILED_건은_dispatch를_호출한다() {
        ReconciliationLog target = logWithRetryCount(1L, ReconciliationType.EVENT_REPUBLISH, 0);
        when(reconciliationLogRepository.findByTypeAndStatusIn(eq(ReconciliationType.EVENT_REPUBLISH), any()))
                .thenReturn(List.of(target));
        when(reconciliationLogRepository.findByTypeAndStatusIn(eq(ReconciliationType.DLT_REPROCESS), any()))
                .thenReturn(List.of());
        when(reconciliationRetryTrigger.dispatch(1L)).thenReturn(null);

        scheduler.retryPendingAndFailed();

        verify(reconciliationRetryTrigger, times(1)).dispatch(1L);
    }

    @Test
    void 최대_재시도_횟수를_넘은_건은_dispatch를_호출하지_않는다() {
        ReconciliationLog exhausted = logWithRetryCount(2L, ReconciliationType.EVENT_REPUBLISH, 5);
        when(reconciliationLogRepository.findByTypeAndStatusIn(eq(ReconciliationType.EVENT_REPUBLISH), any()))
                .thenReturn(List.of(exhausted));
        when(reconciliationLogRepository.findByTypeAndStatusIn(eq(ReconciliationType.DLT_REPROCESS), any()))
                .thenReturn(List.of());

        scheduler.retryPendingAndFailed();

        verify(reconciliationRetryTrigger, never()).dispatch(2L);
    }

    @Test
    void dispatch가_예외를_던져도_나머지_건_처리를_막지_않는다() {
        ReconciliationLog alreadySucceeded = logWithRetryCount(3L, ReconciliationType.EVENT_REPUBLISH, 1);
        ReconciliationLog normal = logWithRetryCount(4L, ReconciliationType.EVENT_REPUBLISH, 1);
        when(reconciliationLogRepository.findByTypeAndStatusIn(eq(ReconciliationType.EVENT_REPUBLISH), any()))
                .thenReturn(List.of(alreadySucceeded, normal));
        when(reconciliationLogRepository.findByTypeAndStatusIn(eq(ReconciliationType.DLT_REPROCESS), any()))
                .thenReturn(List.of());
        when(reconciliationRetryTrigger.dispatch(3L))
                .thenThrow(new ReconciliationAlreadySucceededException(3L));
        when(reconciliationRetryTrigger.dispatch(4L)).thenReturn((ReconciliationLogResponse) null);

        scheduler.retryPendingAndFailed();

        verify(reconciliationRetryTrigger, times(1)).dispatch(3L);
        verify(reconciliationRetryTrigger, times(1)).dispatch(4L);
    }
}
