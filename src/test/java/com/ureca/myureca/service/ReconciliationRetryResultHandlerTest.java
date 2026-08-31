package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReconciliationRetryResultHandlerTest {

    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

    private ReconciliationRetryResultHandler resultHandler;

    @BeforeEach
    void setUp() {
        resultHandler = new ReconciliationRetryResultHandler(reconciliationLogRepository);
    }

    private ReconciliationLog pendingLog() {
        ReconciliationLog log = new ReconciliationLog(
                ReconciliationType.EVENT_REPUBLISH, "event-key-1", null, "coupon-issued-events", "{}", "admin");
        ReflectionTestUtils.setField(log, "id", 1L);
        return log;
    }

    @Test
    void handleSuccess는_로그를_찾아_markSuccess를_호출한다() {
        ReconciliationLog target = pendingLog();
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));

        resultHandler.handleSuccess(1L);

        assertThat(target.getStatus()).isEqualTo(ReconciliationStatus.SUCCESS);
        assertThat(target.getProcessedAt()).isNotNull();
    }

    @Test
    void handleFailure는_로그를_찾아_markFailed를_호출한다() {
        ReconciliationLog target = pendingLog();
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));

        resultHandler.handleFailure(1L, "브로커 연결 실패");

        assertThat(target.getStatus()).isEqualTo(ReconciliationStatus.FAILED);
        assertThat(target.getFailReason()).isEqualTo("브로커 연결 실패");
    }

    @Test
    void 로그를_못_찾으면_예외_없이_조용히_종료한다() {
        when(reconciliationLogRepository.findById(999L)).thenReturn(Optional.empty());

        resultHandler.handleSuccess(999L);
        resultHandler.handleFailure(999L, "이유");

        // 예외가 안 나면 통과 — 별도 assert 불필요
    }
}
