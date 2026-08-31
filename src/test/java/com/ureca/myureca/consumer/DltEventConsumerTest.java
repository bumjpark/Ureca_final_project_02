package com.ureca.myureca.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.BatchListenerFailedException;

@ExtendWith(MockitoExtension.class)
class DltEventConsumerTest {

    @Mock
    private DltEventProcessor processor;

    @InjectMocks
    private DltEventConsumer consumer;

    private static ConsumerRecord<String, CouponIssuedEvent> record(long userId) {
        CouponIssuedEvent event = new CouponIssuedEvent(1L, userId, "rcpt_" + userId, LocalDateTime.now());
        return new ConsumerRecord<>(
                "coupon-issued-events.DLT", 0, 0L, System.currentTimeMillis(), TimestampType.CREATE_TIME,
                0, 0, "1_" + userId, event, new org.apache.kafka.common.header.internals.RecordHeaders(),
                java.util.Optional.empty());
    }

    @Test
    void 정상_처리되면_processSingle이_각_레코드마다_호출된다() {
        List<ConsumerRecord<String, CouponIssuedEvent>> batch = List.of(record(1), record(2));
        doNothing().when(processor).processSingle(any());

        consumer.consume(batch);

        for (ConsumerRecord<String, CouponIssuedEvent> r : batch) {
            verify(processor, times(1)).processSingle(r);
        }
    }

    @Test
    void 이슈11_DataIntegrityViolationException은_정상적인_중복_스킵으로_취급되어_나머지_레코드_처리를_막지_않는다() {
        List<ConsumerRecord<String, CouponIssuedEvent>> batch = List.of(record(1), record(2));
        doThrow(new DataIntegrityViolationException("uk_reconciliation_event_key 위반"))
                .when(processor).processSingle(batch.get(0));
        doNothing().when(processor).processSingle(batch.get(1));

        consumer.consume(batch); // 예외 없이 끝나야 한다

        verify(processor, times(1)).processSingle(batch.get(0));
        verify(processor, times(1)).processSingle(batch.get(1));
    }

    @Test
    void 이슈19_진짜_실패는_그_레코드의_절대_인덱스로_BatchListenerFailedException을_던진다() {
        List<ConsumerRecord<String, CouponIssuedEvent>> batch = List.of(record(1), record(2), record(3));
        doNothing().when(processor).processSingle(batch.get(0));
        doThrow(new RuntimeException("DB 커넥션 오류")).when(processor).processSingle(batch.get(1));

        assertThatThrownBy(() -> consumer.consume(batch))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(ex -> assertThat(((BatchListenerFailedException) ex).getIndex()).isEqualTo(1));

        verify(processor, times(1)).processSingle(batch.get(0));
        verify(processor, times(1)).processSingle(batch.get(1));
        verify(processor, never()).processSingle(batch.get(2));
    }
}
