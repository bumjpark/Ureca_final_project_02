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
}
