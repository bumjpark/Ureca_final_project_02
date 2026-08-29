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
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.dto.response.ReconciliationLogResponse;
import com.ureca.myureca.exception.ReconciliationAlreadySucceededException;
import com.ureca.myureca.exception.ReconciliationLogNotFoundException;
import com.ureca.myureca.exception.ReconciliationTypeNotSupportedException;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReconciliationRetryTriggerTest {

    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

    @Mock
    private KafkaCouponEventProducer kafkaCouponEventProducer;

    @Mock
    private ReconciliationRetryResultHandler resultHandler;

    private ReconciliationRetryTrigger trigger;

    @BeforeEach
    void setUp() {
        trigger = new ReconciliationRetryTrigger(
                reconciliationLogRepository, kafkaCouponEventProducer, resultHandler, new ObjectMapper());
    }

    private ReconciliationLog log(ReconciliationType type, ReconciliationStatus status, String payload) {
        ReconciliationLog log = new ReconciliationLog(type, "event-key-1", null, "coupon-issued-events", payload, "admin");
        ReflectionTestUtils.setField(log, "id", 1L);
        ReflectionTestUtils.setField(log, "status", status);
        return log;
    }

    private String validPayload() {
        return "{\"policyId\":1,\"userId\":100,\"receiptId\":\"rcpt_1\",\"issuedAt\":\"2026-08-24T00:00:00\"}";
    }

    @Test
    void 존재하지_않는_logId면_예외가_발생한다() {
        when(reconciliationLogRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trigger.dispatch(999L))
                .isInstanceOf(ReconciliationLogNotFoundException.class);

        verify(kafkaCouponEventProducer, never()).publishCouponIssuedEventForRetry(any());
    }

    @Test
    void 재발행_가능한_타입이_아니면_예외가_발생하고_카프카는_호출되지_않는다() {
        // REDIS_RECOVER는 "Redis 완전 유실 복구" 이력이라 재발행할 이벤트가 없다 —
        // 세 재발행 타입(EVENT_REPUBLISH/DLT_REPROCESS/ISSUE_REPROCESS)에 속하지 않는 유일한 타입.
        ReconciliationLog target = log(ReconciliationType.REDIS_RECOVER, ReconciliationStatus.PENDING, validPayload());
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> trigger.dispatch(1L))
                .isInstanceOf(ReconciliationTypeNotSupportedException.class);

        verify(kafkaCouponEventProducer, never()).publishCouponIssuedEventForRetry(any());
    }

    @Test
    void ISSUE_REPROCESS는_드리프트_payload로_이벤트를_새로_만들어_발행한다() {
        ReconciliationLog target = log(ReconciliationType.ISSUE_REPROCESS, ReconciliationStatus.PENDING,
                "{\"policyId\":7,\"userId\":999,\"detectedAt\":\"2026-08-29T10:00:00\"}");
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));
        when(kafkaCouponEventProducer.publishCouponIssuedEventForRetry(any()))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());

        trigger.dispatch(1L);

        // receiptId는 logId로 고정 — 같은 로그를 두 번 재처리해도 같은 값이라 Consumer 인박스
        // 체크(coupon_history.request_id UNIQUE)가 1차 방어로 동작한다.
        // issuedAt은 원본 발급 시각이 아니라 드리프트를 발견한 검증 회차 시각이다.
        verify(kafkaCouponEventProducer).publishCouponIssuedEventForRetry(
                new CouponIssuedEvent(7L, 999L, "rcpt_recover_1", LocalDateTime.parse("2026-08-29T10:00:00")));
    }

    @Test
    void ISSUE_REPROCESS_payload가_깨져있으면_즉시_FAILED로_확정된다() {
        ReconciliationLog target = log(ReconciliationType.ISSUE_REPROCESS, ReconciliationStatus.PENDING,
                "이건 JSON이 아님");
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));

        ReconciliationLogResponse response = trigger.dispatch(1L);

        assertThat(response.status()).isEqualTo(ReconciliationStatus.FAILED);
        verify(kafkaCouponEventProducer, never()).publishCouponIssuedEventForRetry(any());
    }

    @Test
    void DLT_REPROCESS_타입도_EVENT_REPUBLISH와_동일하게_재발행된다() {
        ReconciliationLog target = log(ReconciliationType.DLT_REPROCESS, ReconciliationStatus.PENDING, validPayload());
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));
        when(kafkaCouponEventProducer.publishCouponIssuedEventForRetry(any()))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());

        ReconciliationLogResponse response = trigger.dispatch(1L);

        assertThat(target.getRetryCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(ReconciliationStatus.PENDING);
        verify(kafkaCouponEventProducer).publishCouponIssuedEventForRetry(
                new CouponIssuedEvent(1L, 100L, "rcpt_1", LocalDateTime.parse("2026-08-24T00:00:00")));
    }

    @Test
    void 이미_SUCCESS인_로그는_예외가_발생한다() {
        ReconciliationLog target = log(ReconciliationType.EVENT_REPUBLISH, ReconciliationStatus.SUCCESS, validPayload());
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> trigger.dispatch(1L))
                .isInstanceOf(ReconciliationAlreadySucceededException.class);

        verify(kafkaCouponEventProducer, never()).publishCouponIssuedEventForRetry(any());
    }

    @Test
    void payload_역직렬화에_실패하면_즉시_FAILED로_확정되고_카프카는_호출되지_않는다() {
        ReconciliationLog target = log(ReconciliationType.EVENT_REPUBLISH, ReconciliationStatus.PENDING, "이건 JSON이 아님");
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));

        ReconciliationLogResponse response = trigger.dispatch(1L);

        assertThat(response.status()).isEqualTo(ReconciliationStatus.FAILED);
        assertThat(target.getFailReason()).contains("payload 역직렬화 실패");
        assertThat(target.getRetryCount()).isEqualTo(1); // increaseRetryCount는 역직렬화 전에 이미 호출됨
        verify(kafkaCouponEventProducer, never()).publishCouponIssuedEventForRetry(any());
    }

    @Test
    void 정상_PENDING_로그는_retryCount가_증가하고_카프카_발행이_호출된다() {
        ReconciliationLog target = log(ReconciliationType.EVENT_REPUBLISH, ReconciliationStatus.PENDING, validPayload());
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));
        when(kafkaCouponEventProducer.publishCouponIssuedEventForRetry(any()))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>()); // 아직 ack 안 옴(완료 안 시킴)

        ReconciliationLogResponse response = trigger.dispatch(1L);

        assertThat(target.getRetryCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(ReconciliationStatus.PENDING); // ack 전이라 상태는 그대로
        verify(kafkaCouponEventProducer).publishCouponIssuedEventForRetry(
                new CouponIssuedEvent(1L, 100L, "rcpt_1", LocalDateTime.parse("2026-08-24T00:00:00")));
    }

    @Test
    void 카프카_발행이_성공하면_resultHandler_handleSuccess가_호출된다() {
        ReconciliationLog target = log(ReconciliationType.EVENT_REPUBLISH, ReconciliationStatus.PENDING, validPayload());
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaCouponEventProducer.publishCouponIssuedEventForRetry(any())).thenReturn(future);

        trigger.dispatch(1L);
        future.complete(null); // ack 도착 시뮬레이션(내용은 안 씀 — offset 등은 안 쓰기로 함)

        verify(resultHandler).handleSuccess(1L);
    }

    @Test
    void 카프카_발행이_실패하면_resultHandler_handleFailure가_호출된다() {
        ReconciliationLog target = log(ReconciliationType.EVENT_REPUBLISH, ReconciliationStatus.FAILED, validPayload());
        when(reconciliationLogRepository.findById(1L)).thenReturn(Optional.of(target));
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaCouponEventProducer.publishCouponIssuedEventForRetry(any())).thenReturn(future);

        trigger.dispatch(1L);
        future.completeExceptionally(new RuntimeException("브로커 연결 실패"));

        verify(resultHandler).handleFailure(1L, "브로커 연결 실패");
    }
}
