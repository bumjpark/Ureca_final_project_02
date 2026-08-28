package com.ureca.myureca.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), anyInt(), any())).thenReturn(List.of());
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), anyInt(), any())).thenReturn(List.of());

        scheduler.retryPendingAndFailed();

        verify(reconciliationLogRepository).findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), anyInt(), any());
        verify(reconciliationLogRepository).findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), anyInt(), any());
    }

    @Test
    void PENDING_FAILED_건은_dispatch를_호출한다() {
        ReconciliationLog target = logWithRetryCount(1L, ReconciliationType.EVENT_REPUBLISH, 0);
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), anyInt(), any())).thenReturn(List.of(target));
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), anyInt(), any())).thenReturn(List.of());
        when(reconciliationRetryTrigger.dispatch(1L)).thenReturn(null);

        scheduler.retryPendingAndFailed();

        verify(reconciliationRetryTrigger, times(1)).dispatch(1L);
    }

    /**
     * 재시도 소진 건 제외는 반드시 DB 조회 조건이어야 한다 — 조회 후 자바에서 거르면, 소진된 행이
     * PENDING/FAILED 상태로 남아 {@code created_at ASC} 선두를 영구히 점유하다가 페이지 크기만큼
     * 쌓이는 순간 정상 대상이 조회조차 되지 않아 자동 재처리가 통째로 멈춘다.
     */
    @Test
    void 재시도_소진_건_제외는_자바_필터가_아니라_DB_조회_조건으로_넘어간다() {
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                any(), any(), anyInt(), any())).thenReturn(List.of());

        scheduler.retryPendingAndFailed();

        org.mockito.ArgumentCaptor<Integer> limitCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(reconciliationLogRepository, times(2))
                .findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                        any(), any(), limitCaptor.capture(), any());
        org.assertj.core.api.Assertions.assertThat(limitCaptor.getAllValues())
                .as("MAX_AUTO_RETRY_COUNT가 쿼리 조건으로 전달되어야 한다")
                .containsOnly(5);
    }

    @Test
    void 페이지가_소진된_건으로_가득_차도_정상_대상은_계속_조회된다() {
        // 소진 건은 쿼리에서 이미 걸러지므로, 스케줄러가 받는 목록에는 정상 대상만 담긴다.
        ReconciliationLog fresh = logWithRetryCount(10L, ReconciliationType.EVENT_REPUBLISH, 0);
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), anyInt(), any())).thenReturn(List.of(fresh));
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), anyInt(), any())).thenReturn(List.of());
        when(reconciliationRetryTrigger.dispatch(10L)).thenReturn(null);

        scheduler.retryPendingAndFailed();

        verify(reconciliationRetryTrigger, times(1)).dispatch(10L);
    }

    @Test
    void dispatch가_예외를_던져도_나머지_건_처리를_막지_않는다() {
        ReconciliationLog alreadySucceeded = logWithRetryCount(3L, ReconciliationType.EVENT_REPUBLISH, 1);
        ReconciliationLog normal = logWithRetryCount(4L, ReconciliationType.EVENT_REPUBLISH, 1);
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.EVENT_REPUBLISH), any(), anyInt(), any()))
                .thenReturn(List.of(alreadySucceeded, normal));
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                eq(ReconciliationType.DLT_REPROCESS), any(), anyInt(), any())).thenReturn(List.of());
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

        verify(reconciliationLogRepository, never())
                .findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(any(), any(), anyInt(), any());
        verify(reconciliationRetryTrigger, never()).dispatch(any());
    }

    @Test
    void EVENT_REPUBLISH와_DLT_REPROCESS는_서로_다른_락_키를_사용한다() {
        when(reconciliationLogRepository.findByTypeAndStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                any(), any(), anyInt(), any())).thenReturn(List.of());

        scheduler.retryPendingAndFailed();

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(keyCaptor.capture(), anyString(), any(Duration.class));
        List<String> keys = keyCaptor.getAllValues();
        org.assertj.core.api.Assertions.assertThat(keys).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }
}
