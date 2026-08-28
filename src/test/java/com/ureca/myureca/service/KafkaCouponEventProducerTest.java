package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

/**
 * 이슈 #2(Kafka 발행 실패 시 쿠폰이 조용히 증발) 재발 방지 테스트.
 */
@ExtendWith(MockitoExtension.class)
class KafkaCouponEventProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

    private KafkaCouponEventProducer producer;

    private final CouponIssuedEvent event =
            new CouponIssuedEvent(1L, 100L, "rcpt_1", LocalDateTime.parse("2026-08-24T00:00:00"));

    @BeforeEach
    void setUp() {
        producer = new KafkaCouponEventProducer(kafkaTemplate, reconciliationLogRepository, new ObjectMapper());
    }

    @Test
    void 발행_성공시_reconciliation_log에_아무것도_기록하지_않는다() {
        SendResult<String, Object> sendResult = new SendResult<>(
                null, new RecordMetadata(new TopicPartition("coupon-issued-events", 0), 0, 0, 0, 0, 0));
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send("coupon-issued-events", "1_100", event)).thenReturn(future);

        producer.publishCouponIssuedEvent(event);

        verify(reconciliationLogRepository, never()).save(any());
    }

    @Test
    void 발행_실패시_EVENT_REPUBLISH로_reconciliation_log에_적재한다() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send("coupon-issued-events", "1_100", event)).thenReturn(future);
        when(reconciliationLogRepository.existsByEventKey("rcpt_1")).thenReturn(false);

        producer.publishCouponIssuedEvent(event);
        future.completeExceptionally(new RuntimeException("브로커 연결 실패"));

        ArgumentCaptor<ReconciliationLog> captor = ArgumentCaptor.forClass(ReconciliationLog.class);
        verify(reconciliationLogRepository, times(1)).save(captor.capture());
        ReconciliationLog saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(ReconciliationType.EVENT_REPUBLISH);
        assertThat(saved.getEventKey()).isEqualTo("rcpt_1");
        assertThat(saved.getTopic()).isEqualTo("coupon-issued-events");
        assertThat(saved.getPayload()).contains("\"receiptId\":\"rcpt_1\"");
        assertThat(saved.getFailReason()).contains("브로커 연결 실패");
    }

    @Test
    void 이미_같은_receiptId로_적재된_실패건이면_중복_저장하지_않는다() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send("coupon-issued-events", "1_100", event)).thenReturn(future);
        when(reconciliationLogRepository.existsByEventKey("rcpt_1")).thenReturn(true);

        producer.publishCouponIssuedEvent(event);
        future.completeExceptionally(new RuntimeException("브로커 연결 실패"));

        verify(reconciliationLogRepository, never()).save(any());
    }

    @Test
    void MAX_BLOCK_MS_타임아웃_등으로_send_호출_자체가_동기적으로_예외를_던져도_EVENT_REPUBLISH로_적재된다() {
        // 실사고 확인(2026-08-28): KafkaTemplate.doSend()는 producer.send() 자체가 던지는 예외를
        // 감싸지 않고 동기적으로 그대로 재던진다 — whenComplete가 등록되기도 전에 예외가 나서,
        // 이 방어가 없으면 recordPublishFailure가 아예 호출되지 않고 이벤트가 흔적 없이 증발한다
        // (부하테스트 중 Kafka 강제 종료로 라이브 재현: reconciliation_log 없이 459건 영구 유실).
        when(kafkaTemplate.send("coupon-issued-events", "1_100", event))
                .thenThrow(new org.springframework.kafka.KafkaException("Send failed",
                        new org.apache.kafka.common.errors.TimeoutException(
                                "Topic coupon-issued-events not present in metadata after 3000 ms.")));
        when(reconciliationLogRepository.existsByEventKey("rcpt_1")).thenReturn(false);

        producer.publishCouponIssuedEvent(event);

        ArgumentCaptor<ReconciliationLog> captor = ArgumentCaptor.forClass(ReconciliationLog.class);
        verify(reconciliationLogRepository, times(1)).save(captor.capture());
        ReconciliationLog saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(ReconciliationType.EVENT_REPUBLISH);
        assertThat(saved.getEventKey()).isEqualTo("rcpt_1");
    }

    @Test
    void reconciliation_log_저장_자체가_실패해도_예외가_밖으로_전파되지_않는다() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send("coupon-issued-events", "1_100", event)).thenReturn(future);
        when(reconciliationLogRepository.existsByEventKey("rcpt_1")).thenReturn(false);
        when(reconciliationLogRepository.save(any())).thenThrow(new RuntimeException("DB도 죽음"));

        producer.publishCouponIssuedEvent(event);

        // whenComplete 콜백 안에서 예외가 나도 completeExceptionally 자체는 정상적으로 끝나야 한다
        // (콜백에서 던진 예외가 future 체인을 타고 다시 튀어나오지 않아야 함).
        future.completeExceptionally(new RuntimeException("브로커 연결 실패"));
    }

    @Test
    void 대기열_진입_이벤트도_send_호출_자체가_동기적으로_실패해도_예외가_밖으로_전파되지_않는다() {
        com.ureca.myureca.dto.event.QueueJoinEvent joinEvent = new com.ureca.myureca.dto.event.QueueJoinEvent(
                1L, 100L, com.ureca.myureca.domain.queue.QueueStatus.WAITING, 5L, 5L,
                LocalDateTime.parse("2026-08-24T00:00:00"));
        when(kafkaTemplate.send("queue-join-events", "1", joinEvent))
                .thenThrow(new org.springframework.kafka.KafkaException("Send failed",
                        new org.apache.kafka.common.errors.TimeoutException("메타데이터 조회 타임아웃")));

        org.assertj.core.api.Assertions.assertThatCode(() -> producer.publishQueueJoinEvent(joinEvent))
                .doesNotThrowAnyException();
    }
}
