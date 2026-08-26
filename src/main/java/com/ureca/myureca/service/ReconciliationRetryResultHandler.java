package com.ureca.myureca.service;

import com.ureca.myureca.repository.ReconciliationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka 재발행 결과(ack)를 받아 reconciliation_log 확정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationRetryResultHandler {

    private final ReconciliationLogRepository reconciliationLogRepository;

    @Transactional
    public void handleSuccess(Long logId) {
        reconciliationLogRepository.findById(logId).ifPresentOrElse(
                entity -> entity.markSuccess(),
                () -> log.warn("재처리 성공 콜백 도착했으나 로그를 찾을 수 없음. logId={}", logId)
        );
    }

    @Transactional
    public void handleFailure(Long logId, String reason) {
        reconciliationLogRepository.findById(logId).ifPresentOrElse(
                entity -> entity.markFailed(reason),
                () -> log.warn("재처리 실패 콜백 도착했으나 로그를 찾을 수 없음. logId={}", logId)
        );
    }
}
