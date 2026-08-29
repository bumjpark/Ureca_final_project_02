package com.ureca.myureca.dto.event;

import java.time.LocalDateTime;

/**
 * 검증 배치가 발견한 REDIS_ONLY 드리프트 1건. {@code reconciliation_log.payload}에 저장되며,
 * {@link com.ureca.myureca.service.VerificationAsyncTrigger}가 쓰고
 * {@link com.ureca.myureca.service.ReconciliationRetryTrigger}가 읽는다.
 *
 * <p>{@link CouponIssuedEvent}와 달리 이건 Kafka로 나가는 이벤트가 아니라 "재처리 대기열에
 * 남기는 메모"다 — 원본 발급 이벤트를 복원할 수 없어서(발급 시도가 Kafka 발행까지 갔는지조차
 * 알 수 없다) 재처리 시점에 이 값들로 이벤트를 새로 만들어 발행한다.
 *
 * @param detectedAt 이 드리프트를 발견한 검증 회차의 실행 시각. <b>실제 발급 시각이 아니다</b> —
 *                   원본 발급 시각은 어디에도 남아있지 않으므로, 재처리로 복구된 쿠폰의
 *                   {@code issued_at}은 실제보다 늦게 찍힌다(그 사실 자체가 복구된 건임을
 *                   드러내는 단서이기도 하다).
 */
public record RedisOnlyDriftDetail(
        Long policyId,
        Long userId,
        LocalDateTime detectedAt
) {
}
