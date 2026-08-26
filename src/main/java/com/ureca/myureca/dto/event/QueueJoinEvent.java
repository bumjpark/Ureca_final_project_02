package com.ureca.myureca.dto.event;

import com.ureca.myureca.domain.queue.QueueStatus;
import java.time.LocalDateTime;

/**
 * 대기열 진입 이벤트 (Kafka 영속화용).
 *
 * <p>대기열 등록 성공 시 Kafka 토픽(queue-join-events)에 발행되어,
 * Redis 장애 시에도 파티션 오프셋 기반의 영구적인 선착순 감사(Audit) 추적 및 순서 보장을 지원한다.
 *
 * @param policyId 쿠폰 정책 ID
 * @param userId 대기열 진입 유저 ID
 * @param status 대기 상태 (WAITING, ADMITTED)
 * @param rank 내 앞 대기 순번 (0-based)
 * @param seq 선착순 진입 절대 번호표 (1등=1, 2등=2, ...)
 * @param joinedAt 대기열 진입 일시
 */
public record QueueJoinEvent(
        Long policyId,
        Long userId,
        QueueStatus status,
        long rank,
        long seq,
        LocalDateTime joinedAt
) {
}
