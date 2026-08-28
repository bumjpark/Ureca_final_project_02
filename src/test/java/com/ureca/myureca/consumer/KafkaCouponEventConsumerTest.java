package com.ureca.myureca.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 이슈 #15/#16: 청크 분할 → 폴백 → {@link BatchListenerFailedException} 격리 로직 자체를
 * 검증한다. {@link CouponIssuedEventProcessor}는 이미 별도 테스트가 커버하므로 여기서는
 * 오케스트레이션(청크 분할 경계, 폴백 진입 조건, 실패 레코드의 절대 인덱스 계산)만 본다.
 */
@ExtendWith(MockitoExtension.class)
class KafkaCouponEventConsumerTest {

    @Mock
    private CouponIssuedEventProcessor processor;

    @InjectMocks
    private KafkaCouponEventConsumer consumer;

    private static CouponIssuedEvent event(long userId, String receiptId) {
        return new CouponIssuedEvent(1L, userId, receiptId, LocalDateTime.now());
    }

    private static List<CouponIssuedEvent> events(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> event(i, "rcpt_" + i))
                .toList();
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(consumer, "chunkSize", 3);
    }

    @Test
    void 청크가_전부_성공하면_processChunk만_호출되고_processSingle은_호출되지_않는다() {
        List<CouponIssuedEvent> batch = events(3); // chunkSize와 동일 → 청크 1개
        doNothing().when(processor).processChunk(batch);

        consumer.consume(batch);

        verify(processor, times(1)).processChunk(batch);
        verify(processor, never()).processSingle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 배치가_chunkSize보다_크면_여러_청크로_나눠_processChunk를_호출한다() {
        List<CouponIssuedEvent> batch = events(7); // chunkSize=3 → [0,1,2] [3,4,5] [6]
        doNothing().when(processor).processChunk(anyList());

        consumer.consume(batch);

        verify(processor).processChunk(batch.subList(0, 3));
        verify(processor).processChunk(batch.subList(3, 6));
        verify(processor).processChunk(batch.subList(6, 7));
        verify(processor, times(3)).processChunk(anyList());
    }

    @Test
    void 청크_처리가_실패하면_그_청크의_이벤트들만_건별로_폴백한다() {
        List<CouponIssuedEvent> batch = events(3);
        doThrow(new RuntimeException("청크 트랜잭션 롤백")).when(processor).processChunk(batch);
        doNothing().when(processor).processSingle(org.mockito.ArgumentMatchers.any());

        consumer.consume(batch);

        for (CouponIssuedEvent e : batch) {
            verify(processor).processSingle(e);
        }
    }

    @Test
    void 폴백_중_한_건이_실패하면_그_레코드의_절대_인덱스로_BatchListenerFailedException을_던진다() {
        List<CouponIssuedEvent> batch = events(3); // index 0,1,2
        doThrow(new RuntimeException("청크 실패")).when(processor).processChunk(batch);
        doNothing().when(processor).processSingle(batch.get(0));
        doThrow(new RuntimeException("DB 커넥션 오류")).when(processor).processSingle(batch.get(1));

        assertThatThrownBy(() -> consumer.consume(batch))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(ex -> assertThat(((BatchListenerFailedException) ex).getIndex()).isEqualTo(1));

        // index 0은 정상 처리 시도됐고, index 1에서 멈췄으므로 index 2(아직 처리 안 된 나머지)는
        // 이번 시도에서 시도조차 되지 않아야 한다 — 실패 지점 이후로는 재배달을 통해 다시 처리된다.
        verify(processor).processSingle(batch.get(0));
        verify(processor).processSingle(batch.get(1));
        verify(processor, never()).processSingle(batch.get(2));
    }

    @Test
    void 두번째_청크에서_폴백_실패가_나면_절대_인덱스는_배치_전체_기준으로_계산된다() {
        List<CouponIssuedEvent> batch = events(6); // chunkSize=3 → [0,1,2] [3,4,5]
        List<CouponIssuedEvent> firstChunk = batch.subList(0, 3);
        List<CouponIssuedEvent> secondChunk = batch.subList(3, 6);

        doNothing().when(processor).processChunk(firstChunk);
        doThrow(new RuntimeException("두번째 청크 실패")).when(processor).processChunk(secondChunk);
        doNothing().when(processor).processSingle(batch.get(3));
        doThrow(new RuntimeException("DB 커넥션 오류")).when(processor).processSingle(batch.get(4));

        assertThatThrownBy(() -> consumer.consume(batch))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(ex -> assertThat(((BatchListenerFailedException) ex).getIndex()).isEqualTo(4));

        verify(processor, times(1)).processChunk(firstChunk);
        verify(processor, never()).processSingle(batch.get(0)); // 첫 청크는 정상 처리(processChunk)라 폴백 자체가 없음
    }

    @Test
    void 이슈11_폴백_중_DataIntegrityViolationException은_정상적인_중복_스킵으로_취급되고_격리되지_않는다() {
        // processSingle이 UNIQUE/FK 제약 위반을 만나면 (이슈 #11 수정에 따라) 로그만 남기고
        // 그대로 다시 던진다 — 이건 "진짜 실패"가 아니라 정상적인 중복/데이터 스킵이므로
        // BatchListenerFailedException으로 격리하면 안 된다.
        List<CouponIssuedEvent> batch = events(3);
        doThrow(new RuntimeException("청크 실패")).when(processor).processChunk(batch);
        doThrow(new DataIntegrityViolationException("uk_policy_user 위반")).when(processor).processSingle(batch.get(0));
        doNothing().when(processor).processSingle(batch.get(1));
        doNothing().when(processor).processSingle(batch.get(2));

        consumer.consume(batch); // 예외 없이 끝나야 하고, 나머지 이벤트 처리를 막지 않아야 한다

        for (CouponIssuedEvent e : batch) {
            verify(processor).processSingle(e);
        }
    }

    @Test
    void 폴백_전체가_성공하면_예외_없이_정상_종료한다() {
        List<CouponIssuedEvent> batch = events(3);
        doThrow(new RuntimeException("청크 실패")).when(processor).processChunk(batch);
        doNothing().when(processor).processSingle(org.mockito.ArgumentMatchers.any());

        consumer.consume(batch); // 예외 없이 끝나야 한다

        for (CouponIssuedEvent e : batch) {
            verify(processor).processSingle(e);
        }
    }
}
