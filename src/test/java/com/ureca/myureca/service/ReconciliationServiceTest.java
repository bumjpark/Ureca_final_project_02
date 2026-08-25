package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.response.ReconciliationLogResponse;
import com.ureca.myureca.exception.ReconciliationBulkDispatchException;
import com.ureca.myureca.exception.ReconciliationLogNotFoundException;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

    @Mock
    private ReconciliationRetryTrigger reconciliationRetryTrigger;

    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(reconciliationLogRepository, reconciliationRetryTrigger);
    }

    private ReconciliationLog log(long id) {
        ReconciliationLog log = new ReconciliationLog(
                ReconciliationType.EVENT_REPUBLISH, "event-key-" + id, null, "coupon-issued-events", "{}", "admin");
        ReflectionTestUtils.setField(log, "id", id);
        return log;
    }

    private ReconciliationLogResponse response(long id, ReconciliationStatus status) {
        return new ReconciliationLogResponse(id, ReconciliationType.EVENT_REPUBLISH, status,
                "event-key-" + id, "coupon-issued-events", 1, null, null, null);
    }

    @Test
    void logId가_있으면_트리거에_그대로_위임한다() {
        when(reconciliationRetryTrigger.dispatch(1L)).thenReturn(response(1L, ReconciliationStatus.PENDING));

        ReconciliationLogResponse result = reconciliationService.retryOne(1L);

        assertThat(result.id()).isEqualTo(1L);
        verify(reconciliationRetryTrigger).dispatch(1L);
    }

    @Test
    void 존재하지_않는_logId면_트리거가_던진_예외가_그대로_전파된다() {
        when(reconciliationRetryTrigger.dispatch(999L))
                .thenThrow(new ReconciliationLogNotFoundException(999L));

        assertThatThrownBy(() -> reconciliationService.retryOne(999L))
                .isInstanceOf(ReconciliationLogNotFoundException.class);
    }

    @Test
    void logId가_없으면_대상_전체를_순회한다() {
        ReconciliationLog log1 = log(1L);
        ReconciliationLog log2 = log(2L);
        when(reconciliationLogRepository.findByTypeAndStatusIn(
                ReconciliationType.EVENT_REPUBLISH, List.of(ReconciliationStatus.PENDING, ReconciliationStatus.FAILED)))
                .thenReturn(List.of(log1, log2));
        when(reconciliationRetryTrigger.dispatch(1L)).thenReturn(response(1L, ReconciliationStatus.PENDING));
        when(reconciliationRetryTrigger.dispatch(2L)).thenReturn(response(2L, ReconciliationStatus.PENDING));

        List<ReconciliationLogResponse> results = reconciliationService.retryAll();

        assertThat(results).hasSize(2);
        assertThat(results).extracting(ReconciliationLogResponse::id).containsExactly(1L, 2L);
    }

    @Test
    void 전체_재처리_중_하나가_실패하면_이미_접수된_id_목록을_담아_예외를_던진다() {
        ReconciliationLog log1 = log(1L);
        ReconciliationLog log2 = log(2L);
        when(reconciliationLogRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(log1, log2));
        when(reconciliationRetryTrigger.dispatch(1L)).thenReturn(response(1L, ReconciliationStatus.PENDING));
        when(reconciliationRetryTrigger.dispatch(2L)).thenThrow(new RuntimeException("DB 순단"));

        assertThatThrownBy(() -> reconciliationService.retryAll())
                .isInstanceOf(ReconciliationBulkDispatchException.class)
                .satisfies(e -> {
                    var bulkException = (ReconciliationBulkDispatchException) e;
                    assertThat(bulkException.getDispatchedLogIds()).containsExactly(1L);
                });
    }

    @Test
    void 대상이_없으면_빈_리스트를_반환한다() {
        when(reconciliationLogRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of());

        List<ReconciliationLogResponse> results = reconciliationService.retryAll();

        assertThat(results).isEmpty();
        verify(reconciliationRetryTrigger, never()).dispatch(any());
    }
}
