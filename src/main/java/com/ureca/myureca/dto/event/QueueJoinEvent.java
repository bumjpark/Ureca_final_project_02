package com.ureca.myureca.dto.event;

import com.ureca.myureca.domain.queue.QueueStatus;
import java.time.LocalDateTime;

/**
 * 대기열 진입 이벤트 (Kafka 영속화용).
 *
 * <p>대기열 등록 성공 시 Kafka 토픽(queue-join-events)에 발행되어,
 * Redis 장애 시에도 파티션 오프셋 기반의 영구적인 선착순 감사(Audit) 추적 및 순서 보장을 지원한다.
 */
public record QueueJoinEvent(
        Long policyId,
        Long userId,
        QueueStatus status,
        long rank,
        LocalDateTime joinedAt
) {
}
