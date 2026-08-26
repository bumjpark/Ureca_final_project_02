package com.ureca.myureca.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Kafka Consumer 재시도 모두 실패 시 호출되는 복구 로직 확장 포인트.
 *
 * <p>이번 이슈(UBM-40)에서는 {@link LoggingFailureRecoverer}로 구현하여
 * 구조화된 실패 로그만 기록한다.
 *
 * <p>다음 이슈(DLT)에서 {@code DltPublishingFailureRecoverer}로 교체할 때는
 * {@link KafkaConsumerConfig}의 Bean 선언만 교체하면 되며, Consumer 비즈니스 로직 수정은 불필요하다.
 */
public interface ConsumerFailureRecoverer {

    /**
     * 재시도 모두 실패한 레코드를 처리한다.
     *
     * @param record  실패한 Kafka 레코드
     * @param ex      마지막으로 발생한 예외
     */
    void recover(ConsumerRecord<?, ?> record, Exception ex);
}
