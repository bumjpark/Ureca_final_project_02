package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.queue.QueueStatus;

/**
 * POST /api/queue/join 응답.
 *
 * <ul>
 *   <li>{@code status}                – WAITING(대기) 또는 ADMITTED(즉시 입장)</li>
 *   <li>{@code rank}                  – 내 앞 대기 인원수 (0이면 즉시 입장 가능)</li>
 *   <li>{@code activeToken}           – ADMITTED 시 발급되는 대기열 통과 토큰, WAITING 시 null</li>
 *   <li>{@code estimatedWaitSeconds}  – 예상 대기 시간(초). rank × 기준 처리 시간으로 추정.</li>
 * </ul>
 */
public record QueueJoinResponse(
        QueueStatus status,
        long rank,
        String activeToken,
        long estimatedWaitSeconds
) {

    /** 대기 중: 토큰 없이 순번과 예상 대기 시간만 반환 */
    public static QueueJoinResponse waiting(long rank, long estimatedWaitSeconds) {
        return new QueueJoinResponse(QueueStatus.WAITING, rank, null, estimatedWaitSeconds);
    }

    /** 즉시 입장: activeToken 발급 */
    public static QueueJoinResponse admitted(String activeToken) {
        return new QueueJoinResponse(QueueStatus.ADMITTED, 0L, activeToken, 0L);
    }
}
