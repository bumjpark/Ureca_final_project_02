package com.ureca.myureca.consumer;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * DLT({@code coupon-issued-events.DLT})로 이적재된 메시지 1건을 {@code reconciliation_log}에
 * {@link ReconciliationType#DLT_REPROCESS} 행으로 적재하는 단위 처리기.
 *
 * <p>이렇게 DB로 옮겨 적재해두면, 이미 있는 {@code GET /api/admin/reconciliation/logs?type=DLT_REPROCESS}
 * 조회 API를 그대로 재사용해서 "DLT에 뭐가 쌓였는지"를 확인할 수 있다 — Kafka 토픽을 직접 조회하는
 * 새 API를 따로 만들 필요가 없다.
 *
 * <p>{@link DltEventConsumer}(@KafkaListener)로부터 레코드 단위로 호출되며, 이 메서드 단위로
 * 트랜잭션이 분리된다(self-invoke AOP 프록시 문제 회피 — {@link CouponIssuedEventProcessor}와 동일 패턴).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DltEventProcessor {

    static final String HEADER_FAILURE_REASON = DltPublishingFailureRecoverer.HEADER_FAILURE_REASON;
    static final String HEADER_FAILURE_MESSAGE = DltPublishingFailureRecoverer.HEADER_FAILURE_MESSAGE;
    static final String HEADER_ORIGINAL_TOPIC = DltPublishingFailureRecoverer.HEADER_ORIGINAL_TOPIC;
    static final String HEADER_ORIGINAL_PARTITION = DltPublishingFailureRecoverer.HEADER_ORIGINAL_PARTITION;
    static final String HEADER_ORIGINAL_OFFSET = DltPublishingFailureRecoverer.HEADER_ORIGINAL_OFFSET;

    private final ReconciliationLogRepository reconciliationLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * DLT 레코드 1건을 reconciliation_log 행으로 저장한다.
     *
     * @param record DLT 토픽에서 수신한 레코드. value는 원본 이벤트가 정상 파싱됐으면
     *               {@link CouponIssuedEvent}, 역직렬화 자체가 실패했던 메시지면 null이다.
     */
    @Transactional
    public void processSingle(ConsumerRecord<String, CouponIssuedEvent> record) {
        String eventKey = buildEventKey(record);

        // 1차 방어: 인박스 패턴 — 컨슈머 재시작/리밸런싱으로 같은 DLT 레코드가 재배달돼도 중복 적재하지 않는다.
        if (reconciliationLogRepository.existsByEventKey(eventKey)) {
            log.info("[DltEventConsumer] 이미 적재된 DLT 레코드 스킵 — eventKey={}", eventKey);
            return;
        }

        String originalTopic = header(record, HEADER_ORIGINAL_TOPIC);
        String failureReason = header(record, HEADER_FAILURE_REASON);
        String failureMessage = header(record, HEADER_FAILURE_MESSAGE);
        String payload = toPayloadJson(record.value());

        try {
            ReconciliationLog reconciliationLog = new ReconciliationLog(
                    ReconciliationType.DLT_REPROCESS,
                    eventKey,
                    null,   // coupon_issue_id: DLT 단계에서는 아직 발급 자체가 안 됐으므로 연결할 대상이 없다.
                    originalTopic != null ? originalTopic : record.topic(),
                    payload,
                    null    // requestedBy: 사람이 아니라 이 컨슈머가 자동으로 적재한 것이므로 비워둔다.
            );
            reconciliationLog.recordOriginalFailure(summarizeFailure(failureReason, failureMessage));
            reconciliationLogRepository.save(reconciliationLog);

            log.info("[DltEventConsumer] DLT 레코드 적재 완료 — eventKey={}, originalTopic={}, failureReason={}",
                    eventKey, originalTopic, failureReason);
        } catch (DataIntegrityViolationException e) {
            // 2차 방어: eventKey UNIQUE(uk_reconciliation_event_key) 위반 — 동시 진입으로 인한 중복.
            log.info("[DltEventConsumer] eventKey UNIQUE 제약 위반 → 중복 — eventKey={}", eventKey);
            // 여기서 삼키고 정상 리턴하면 안 된다(이슈 #11과 동일한 버그, 실측으로 확인됨):
            // ReconciliationLog는 IDENTITY 전략이라 save() 시점에 즉시 flush되고, 그 INSERT가
            // UNIQUE 위반으로 실패하면서 Hibernate가 이 트랜잭션을 이미 rollback-only로 마킹해둔다.
            // 예외를 삼킨 채 메서드가 정상 종료되면 Spring이 COMMIT을 시도하다가
            // UnexpectedRollbackException을 새로 던지고, 이게 DltEventConsumer로 전파되면
            // 정상적인 중복 스킵이 "진짜 실패"로 오인돼 불필요한 재시도를 유발한다. 같은 예외를
            // 다시 던져 트랜잭션을 예외 기반으로 정상 롤백시킨다 — 호출부가 "정상적인 스킵"으로
            // 처리한다.
            throw e;
        }
    }

    /** reconciliation_log.fail_reason(length=255)에 들어갈 "원인 - 메시지" 요약. 길면 잘라낸다. */
    private String summarizeFailure(String failureReason, String failureMessage) {
        String reason = (failureReason != null) ? failureReason : "UNKNOWN_ERROR";
        String summary = (failureMessage != null) ? reason + " - " + failureMessage : reason;
        return summary.length() > 255 ? summary.substring(0, 255) : summary;
    }

    /**
     * reconciliation_log.event_key로 쓸 고유 키를 만든다.
     * receiptId를 알 수 있으면(정상 파싱된 이벤트) 그대로 쓰고, 역직렬화 자체가 실패해 receiptId를
     * 알 수 없으면 "원본 토픽:파티션:오프셋"으로 대체한다 — Kafka 레코드 좌표 자체가 유일하기 때문이다.
     */
    private String buildEventKey(ConsumerRecord<String, CouponIssuedEvent> record) {
        CouponIssuedEvent event = record.value();
        if (event != null && event.receiptId() != null) {
            return event.receiptId();
        }
        String originalTopic = header(record, HEADER_ORIGINAL_TOPIC);
        String originalPartition = header(record, HEADER_ORIGINAL_PARTITION);
        String originalOffset = header(record, HEADER_ORIGINAL_OFFSET);
        return "dlt:%s:%s:%s".formatted(
                originalTopic != null ? originalTopic : record.topic(),
                originalPartition != null ? originalPartition : record.partition(),
                originalOffset != null ? originalOffset : record.offset());
    }

    private String toPayloadJson(CouponIssuedEvent event) {
        if (event == null) {
            return null;
        }
        return objectMapper.writeValueAsString(event);
    }

    private String header(ConsumerRecord<?, ?> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
