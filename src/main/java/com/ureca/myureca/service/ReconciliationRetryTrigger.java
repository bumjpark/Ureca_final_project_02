package com.ureca.myureca.service;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.dto.response.ReconciliationLogResponse;
import com.ureca.myureca.exception.ReconciliationAlreadySucceededException;
import com.ureca.myureca.exception.ReconciliationLogNotFoundException;
import com.ureca.myureca.exception.ReconciliationTypeNotSupportedException;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 재처리 로그 한 건을 접수·발행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationRetryTrigger {

    private final ReconciliationLogRepository reconciliationLogRepository;
    private final KafkaCouponEventProducer kafkaCouponEventProducer;
    private final ReconciliationRetryResultHandler resultHandler;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReconciliationLogResponse dispatch(Long logId) {
        ReconciliationLog logEntity = reconciliationLogRepository.findById(logId)
                .orElseThrow(() -> new ReconciliationLogNotFoundException(logId));

        if (logEntity.getType() != ReconciliationType.EVENT_REPUBLISH) {
            throw new ReconciliationTypeNotSupportedException(logEntity.getId(), logEntity.getType());
        }
        if (logEntity.getStatus() == ReconciliationStatus.SUCCESS) {
            throw new ReconciliationAlreadySucceededException(logEntity.getId());
        }

        logEntity.increaseRetryCount();

        CouponIssuedEvent event;
        try {
            event = objectMapper.readValue(logEntity.getPayload(), CouponIssuedEvent.class);
        } catch (Exception e) {
            // payload 자체가 깨진 건 Kafka까지 갈 것도 없이 여기서 바로 실패 확정한다 —
            // 재시도해봐야 같은 이유로 계속 실패할 동기 오류라서다.
            logEntity.markFailed(summarize("payload 역직렬화 실패: " + e.getMessage()));
            return ReconciliationLogResponse.from(logEntity);
        }

        kafkaCouponEventProducer.publishCouponIssuedEventForRetry(event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        resultHandler.handleFailure(logId, summarize(ex.getMessage()));
                    } else {
                        resultHandler.handleSuccess(logId);
                    }
                });

        // ack이 아직 안 왔으므로 현재(접수 직후) 상태 그대로 응답한다 — 검증 배치의
        // PENDING 접수 후 비동기 완료와 동일한 계약.
        return ReconciliationLogResponse.from(logEntity);
    }

    /** fail_reason이 VARCHAR(255)라 그 안에 맞게 자른다 — 안 그러면 저장 시점에 DataTruncation이 난다. */
    private String summarize(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 255 ? message.substring(0, 255) : message;
    }
}
