package com.ureca.myureca.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 이슈 #21: 분산 락으로 다중 인스턴스 동시 실행을 막고, 조회를 배치 크기로 제한하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationAutoRetrySchedulerTest {

    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

    @Mock
    private ReconciliationRetryTrigger reconciliationRetryTrigger;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ReconciliationAutoRetryScheduler scheduler;

    private ReconciliationLog logWithRetryCount(Long id, ReconciliationType type, int retryCount) {
        ReconciliationLog log = new ReconciliationLog(type, "event-key-" + id, null, "coupon-issued-events",
                "{\"policyId\":1,\"userId\":100,\"receiptId\":\"rcpt_1\",\"issuedAt\":\"2026-08-24T00:00:00\"}", null);
        ReflectionTestUtils.setField(log, "id", id);
        ReflectionTestUtils.setField(log, "retryCount", retryCount);
        return log;
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 기본값: 락 항상 획득 성공 (락 실패를 직접 검증하는 테스트에서만 오버라이드하며, 그 경우
        // 이 기본 스텁은 실제로 안 쓰이므로 lenient 처리한다).
        org.mockito.Mockito.lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    void EVENT_REPUBLISH와_DLT_REPROCESS_둘_다_대상으로_조회한다() {
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), any())).thenReturn(List.of());
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), any())).thenReturn(List.of());

        scheduler.retryPendingAndFailed();

        verify(reconciliationLogRepository).findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), any());
        verify(reconciliationLogRepository).findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), any());
    }

    @Test
    void PENDING_FAILED_건은_dispatch를_호출한다() {
        ReconciliationLog target = logWithRetryCount(1L, ReconciliationType.EVENT_REPUBLISH, 0);
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), any())).thenReturn(List.of(target));
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), any())).thenReturn(List.of());
        when(reconciliationRetryTrigger.dispatch(1L)).thenReturn(null);

        scheduler.retryPendingAndFailed();

        verify(reconciliationRetryTrigger, times(1)).dispatch(1L);
    }

    @Test
    void 최대_재시도_횟수를_넘은_건은_dispatch를_호출하지_않는다() {
        ReconciliationLog exhausted = logWithRetryCount(2L, ReconciliationType.EVENT_REPUBLISH, 5);
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), any())).thenReturn(List.of(exhausted));
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), any())).thenReturn(List.of());

        scheduler.retryPendingAndFailed();

        verify(reconciliationRetryTrigger, never()).dispatch(2L);
    }

    @Test
    void dispatch가_예외를_던져도_나머지_건_처리를_막지_않는다() {
        ReconciliationLog alreadySucceeded = logWithRetryCount(3L, ReconciliationType.EVENT_REPUBLISH, 1);
        ReconciliationLog normal = logWithRetryCount(4L, ReconciliationType.EVENT_REPUBLISH, 1);
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), any()))
                .thenReturn(List.of(alreadySucceeded, normal));
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), any())).thenReturn(List.of());
        when(reconciliationRetryTrigger.dispatch(3L))
                .thenThrow(new ReconciliationAlreadySucceededException(3L));
        when(reconciliationRetryTrigger.dispatch(4L)).thenReturn((ReconciliationLogResponse) null);

        scheduler.retryPendingAndFailed();

        verify(reconciliationRetryTrigger, times(1)).dispatch(3L);
        verify(reconciliationRetryTrigger, times(1)).dispatch(4L);
    }

    @Test
    void 락_획득에_실패한_type은_조회_자체를_건너뛴다() {
        // 이슈 #21: 다른 인스턴스가 이미 이 type을 처리 중이면 조회/dispatch를 아예 시도하지 않아야
        // retryCount가 여러 인스턴스에 의해 중복 소진되지 않는다.
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        scheduler.retryPendingAndFailed();

        verify(reconciliationLogRepository, never()).findByTypeAndStatusInOrderByCreatedAtAsc(any(), any(), any());
        verify(reconciliationRetryTrigger, never()).dispatch(any());
    }

    @Test
    void EVENT_REPUBLISH와_DLT_REPROCESS는_서로_다른_락_키를_사용한다() {
        when(reconciliationLogRepository.findByTypeAndStatusInOrderByCreatedAtAsc(any(), any(), any()))
                .thenReturn(List.of());

        scheduler.retryPendingAndFailed();

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(keyCaptor.capture(), anyString(), any(Duration.class));
        List<String> keys = keyCaptor.getAllValues();
        org.assertj.core.api.Assertions.assertThat(keys).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }
}
