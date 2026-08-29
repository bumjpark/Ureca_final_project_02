package com.ureca.myureca.service;

import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.dto.event.RedisOnlyDriftDetail;
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

        // 세 타입 모두 최종 동작은 "coupon-issued-events 토픽으로 발행"이라 같은 경로를 탄다.
        // 다른 건 payload에서 이벤트를 얻는 방법뿐이다(아래 toEvent 참고).
        //   EVENT_REPUBLISH — Kafka 발행 자체가 실패. payload가 원본 이벤트 그대로다.
        //   DLT_REPROCESS   — Consumer 처리 실패로 DLT에 쌓임. 역시 원본 이벤트.
        //   ISSUE_REPROCESS — 검증 배치가 발견한 REDIS_ONLY 드리프트. 원본 이벤트가 없어
        //                     policyId/userId로 새로 만들어 발행한다.
        // 재발행된 이벤트는 KafkaCouponEventConsumer → CouponIssuedEventProcessor의 인박스 체크(1차) +
        // DB UNIQUE 제약(2차, uk_policy_user) 덕분에, 원본이 사실은 이미 반영돼 있었더라도 중복 발급되지 않는다.
        if (logEntity.getType() != ReconciliationType.EVENT_REPUBLISH
                && logEntity.getType() != ReconciliationType.DLT_REPROCESS
                && logEntity.getType() != ReconciliationType.ISSUE_REPROCESS) {
            throw new ReconciliationTypeNotSupportedException(logEntity.getId(), logEntity.getType());
        }
        if (logEntity.getStatus() == ReconciliationStatus.SUCCESS) {
            throw new ReconciliationAlreadySucceededException(logEntity.getId());
        }

        logEntity.increaseRetryCount();

        CouponIssuedEvent event;
        try {
            event = toEvent(logEntity);
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

    /**
     * 재처리 로그 1건에서 발행할 이벤트를 얻는다.
     *
     * <p>EVENT_REPUBLISH/DLT_REPROCESS는 payload가 원본 {@link CouponIssuedEvent} 그대로라 그냥
     * 역직렬화한다. ISSUE_REPROCESS(검증 배치가 발견한 REDIS_ONLY 드리프트)만 사정이 다르다 —
     * 원본 이벤트가 애초에 어디에도 안 남았으므로({@code VerificationAsyncTrigger.registerRedisOnlyDrift}
     * 주석 참고) payload에 적어둔 policyId/userId로 이벤트를 새로 만든다.
     *
     * <p><b>receiptId를 logId로 고정하는 이유</b>: 같은 로그를 두 번 재처리해도 같은 값이 나와야
     * Consumer의 인박스 체크({@code coupon_history.request_id} UNIQUE)가 1차 방어로 동작한다.
     * 매번 새 UUID를 만들면 인박스를 그냥 통과해 {@code uk_policy_user}(2차 방어)까지 가서야
     * 걸리는데, 그건 정상 동작이긴 해도 로그에 제약 위반이 남아 진짜 이상 징후와 섞인다.
     *
     * <p><b>issuedAt이 실제 발급 시각이 아니다</b>: 원본 시각은 복원할 수 없어 드리프트를 발견한
     * 검증 회차 시각을 쓴다. 복구된 쿠폰의 {@code issued_at}은 실제보다 늦게 찍히며, 이건
     * 감수하는 부정확성이다(그 자체가 "복구된 건"임을 드러내는 단서이기도 하다).
     */
    private CouponIssuedEvent toEvent(ReconciliationLog logEntity) {
        if (logEntity.getType() != ReconciliationType.ISSUE_REPROCESS) {
            return objectMapper.readValue(logEntity.getPayload(), CouponIssuedEvent.class);
        }
        RedisOnlyDriftDetail detail =
                objectMapper.readValue(logEntity.getPayload(), RedisOnlyDriftDetail.class);
        return new CouponIssuedEvent(
                detail.policyId(),
                detail.userId(),
                "rcpt_recover_" + logEntity.getId(),
                detail.detectedAt());
    }

    /** fail_reason이 VARCHAR(255)라 그 안에 맞게 자른다 — 안 그러면 저장 시점에 DataTruncation이 난다. */
    private String summarize(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 255 ? message.substring(0, 255) : message;
    }
}
