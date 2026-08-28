package com.ureca.myureca.consumer;

import com.ureca.myureca.dto.event.CouponIssuedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

/**
 * coupon-issued-events 토픽 구독 Consumer.
 *
 * <p>오케스트레이션 전담: 배치 수신 → 청크 분할 → 청크 단위 위임, 실패 시 건별 폴백.
 * 트랜잭션은 없다. 실제 저장 로직과 @Transactional은 {@link CouponIssuedEventProcessor}에 있다.
 *
 * <p>self-invoke 방지를 위해 Processor를 별도 Bean으로 분리한다.
 * 같은 클래스 내에 @KafkaListener와 @Transactional 메서드가 함께 있으면
 * Spring AOP 프록시가 self-invoke를 인터셉트하지 못해 트랜잭션이 적용되지 않는다.
 *
 * <p>concurrency 설정 주의:
 * concurrency 값은 파티션 수를 초과하면 안 된다. 초과 시 일부 스레드가 파티션을 배정받지 못해
 * 유휴 상태가 되고, 파티션당 단일 스레드 처리 보장이 깨진다.
 * 실제 파티션 수는 {@code kafka-topics.sh --describe --topic coupon-issued-events}로 확인할 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaCouponEventConsumer {

    private static final String TOPIC = "coupon-issued-events";

    private final CouponIssuedEventProcessor processor;

    @Value("${coupon.kafka.consumer.chunk-size:100}")
    private int chunkSize;

    /**
     * 배치 수신 → 청크 분할 → 청크 단위 처리(정상 경로) → 실패 시 그 청크만 건별 폴백.
     *
     * <p>배치를 통째로 하나의 트랜잭션으로 묶지 않는 이유는 이전과 동일(순서 보존,
     * poison-pill 하나로 전체가 물귀신처럼 실패하는 범위 최소화)이지만, 이벤트 1건마다
     * 트랜잭션을 여는 것도 커밋(디스크 fsync 포함) 횟수가 이벤트 수만큼 늘어나 비효율적이다.
     * 그 중간 지점으로 {@code chunkSize}(기본 100)건씩 묶어 처리한다.
     *
     * <p>청크 내부/청크 간 순서는 원본 배치 순서를 그대로 유지한다({@link List#subList}) —
     * 같은 파티션 키(policyId_userId)로 보장된 파티션 내 순서를 애플리케이션 레벨에서도
     * 지키기 위함이다.
     *
     * <p>{@link CouponIssuedEventProcessor#processChunk}가 예외를 던지면(청크 안에 문제
     * 있는 이벤트가 있어 트랜잭션 전체가 롤백된 경우) 그 청크에 한해
     * {@link CouponIssuedEventProcessor#processSingle}로 건별 재처리한다 — 청크 안의
     * 정상 이벤트를 구제하기 위함이다.
     *
     * <p><b>건별 폴백 중 하나가 또 실패하면(DB 커넥션 오류 등 진짜 일시 장애)</b> 그 예외를
     * 그대로 밖으로 던지지 않는다. 배치 리스너에서 일반 예외를 던지면 Spring Kafka는 "이 poll
     * 배치 전체가 실패했다"고 간주해, 재시도가 모두 소진된 뒤 recoverer를 poll 배치에 담긴
     * 레코드 전부(이미 커밋된 앞쪽 레코드까지 포함)에 대해 호출한다 — 배치 크기가 클수록
     * (예: max.poll.records=5000) 애먼 레코드까지 무더기로 DLT에 실리는 사고로 번진다
     * (실측: 896건이 한 번에 DLT로 넘어간 사례 확인, issue #15).
     * 대신 {@link BatchListenerFailedException}에 실패한 레코드의 poll 배치 내 절대 인덱스를
     * 실어 던진다 — Spring Kafka가 정확히 그 레코드의 오프셋으로만 seek-back하므로, 재시도/DLT
     * 범위가 실제로 실패한 레코드(및 그 뒤로 아직 처리 안 된 레코드)로만 좁혀진다.
     *
     * @param events Kafka로 수신한 CouponIssuedEvent 배치 (max.poll.records 크기)
     */
    @KafkaListener(
            topics = TOPIC,
            groupId = "${coupon.kafka.consumer.group-id:coupon-issue-consumer-group}",
            concurrency = "${coupon.kafka.consumer.concurrency:3}",
            containerFactory = "couponIssuedEventListenerContainerFactory"
    )
    public void consume(List<CouponIssuedEvent> events) {
        log.debug("[KafkaConsumer] 배치 수신 — topic={}, size={}, chunkSize={}", TOPIC, events.size(), chunkSize);
        for (int start = 0; start < events.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, events.size());
            List<CouponIssuedEvent> chunk = events.subList(start, end);
            try {
                processor.processChunk(chunk);
            } catch (Exception e) {
                log.warn("[KafkaConsumer] 청크 처리 실패 — 건별 재처리로 폴백. size={}", chunk.size(), e);
                for (int i = 0; i < chunk.size(); i++) {
                    CouponIssuedEvent event = chunk.get(i);
                    try {
                        processor.processSingle(event);
                    } catch (DataIntegrityViolationException dive) {
                        // 진짜 실패가 아니다(이슈 #11) — UNIQUE/FK 제약 위반으로 트랜잭션을 정상
                        // 롤백시키기 위해 processSingle이 일부러 다시 던진 것뿐이다. 이미 처리 로그는
                        // processSingle 안에서 남겼으므로 여기서는 조용히 다음 이벤트로 넘어간다.
                        // BatchListenerFailedException으로 격리하면 안 된다 — 그러면 정상적인 중복
                        // 스킵 건이 재시도/DLT 대상으로 잘못 취급된다.
                    } catch (Exception singleEx) {
                        int absoluteIndex = start + i;
                        log.warn("[KafkaConsumer] 건별 재처리도 실패 — 이 레코드(index={})만 격리해 재시도/DLT 대상으로 넘김. "
                                        + "receiptId={}, policyId={}, userId={}",
                                absoluteIndex, event.receiptId(), event.policyId(), event.userId(), singleEx);
                        throw new BatchListenerFailedException(
                                "이벤트 단건 처리 실패 — receiptId=" + event.receiptId(), singleEx, absoluteIndex);
                    }
                }
            }
        }
    }
}
