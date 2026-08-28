package com.ureca.myureca.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DltEventProcessorTest {

    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

    private DltEventProcessor processor;

    private static final String DLT_TOPIC = "coupon-issued-events.DLT";
    private static final String RECEIPT_ID = "rcpt_abc123def456";

    @BeforeEach
    void setUp() {
        processor = new DltEventProcessor(reconciliationLogRepository, new ObjectMapper());
    }

    private ConsumerRecord<String, CouponIssuedEvent> recordWith(CouponIssuedEvent event) {
        ConsumerRecord<String, CouponIssuedEvent> record = new ConsumerRecord<>(
                DLT_TOPIC, 0, 10L, System.currentTimeMillis(), TimestampType.CREATE_TIME,
                0, 0, "5_1", event, new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
        record.headers().add(DltEventProcessor.HEADER_ORIGINAL_TOPIC, "coupon-issued-events".getBytes(StandardCharsets.UTF_8));
        record.headers().add(DltEventProcessor.HEADER_ORIGINAL_PARTITION, "2".getBytes(StandardCharsets.UTF_8));
        record.headers().add(DltEventProcessor.HEADER_ORIGINAL_OFFSET, "42".getBytes(StandardCharsets.UTF_8));
        record.headers().add(DltEventProcessor.HEADER_FAILURE_REASON, "DB_CONNECTION_ERROR".getBytes(StandardCharsets.UTF_8));
        record.headers().add(DltEventProcessor.HEADER_FAILURE_MESSAGE, "boom".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    void 정상_파싱된_이벤트는_receiptId를_eventKey로_적재한다() {
        CouponIssuedEvent event = new CouponIssuedEvent(5L, 1L, RECEIPT_ID, LocalDateTime.now());
        when(reconciliationLogRepository.existsByEventKey(RECEIPT_ID)).thenReturn(false);

        processor.processSingle(recordWith(event));

        ArgumentCaptor<ReconciliationLog> captor = ArgumentCaptor.forClass(ReconciliationLog.class);
        verify(reconciliationLogRepository).save(captor.capture());

        ReconciliationLog saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(ReconciliationType.DLT_REPROCESS);
        assertThat(saved.getEventKey()).isEqualTo(RECEIPT_ID);
        assertThat(saved.getTopic()).isEqualTo("coupon-issued-events");
        assertThat(saved.getPayload()).contains(RECEIPT_ID);
        assertThat(saved.getFailReason()).isEqualTo("DB_CONNECTION_ERROR - boom");
    }

    @Test
    void 역직렬화_실패로_value가_null이면_토픽_파티션_오프셋으로_eventKey를_구성한다() {
        when(reconciliationLogRepository.existsByEventKey("dlt:coupon-issued-events:2:42")).thenReturn(false);

        processor.processSingle(recordWith(null));

        ArgumentCaptor<ReconciliationLog> captor = ArgumentCaptor.forClass(ReconciliationLog.class);
        verify(reconciliationLogRepository).save(captor.capture());

        ReconciliationLog saved = captor.getValue();
        assertThat(saved.getEventKey()).isEqualTo("dlt:coupon-issued-events:2:42");
        assertThat(saved.getPayload()).isNull();
    }

    @Test
    void 이미_적재된_eventKey면_save를_호출하지_않고_정상_종료한다() {
        CouponIssuedEvent event = new CouponIssuedEvent(5L, 1L, RECEIPT_ID, LocalDateTime.now());
        when(reconciliationLogRepository.existsByEventKey(RECEIPT_ID)).thenReturn(true);

        assertThatCode(() -> processor.processSingle(recordWith(event))).doesNotThrowAnyException();

        verify(reconciliationLogRepository, never()).save(any());
    }

    @Test
    void eventKey_UNIQUE_제약_위반시_예외가_전파되지_않는다() {
        CouponIssuedEvent event = new CouponIssuedEvent(5L, 1L, RECEIPT_ID, LocalDateTime.now());
        when(reconciliationLogRepository.existsByEventKey(RECEIPT_ID)).thenReturn(false);
        when(reconciliationLogRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_reconciliation_event_key 위반"));

        assertThatCode(() -> processor.processSingle(recordWith(event))).doesNotThrowAnyException();
    }
}
