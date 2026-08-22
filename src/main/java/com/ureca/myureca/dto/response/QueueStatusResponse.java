package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.queue.QueueStatus;

/**
 * GET /api/queue/status 응답 DTO.
 *
 * <ul>
 *   <li>{@code status}               – WAITING(대기), ADMITTED(입장 가능), SOLD_OUT(품절)</li>
 *   <li>{@code rank}                 – 내 앞 대기 인원수 (0이면 내 차례)</li>
 *   <li>{@code activeToken}          – ADMITTED 시 발급된 활성 토큰, WAITING/SOLD_OUT 시 null</li>
 *   <li>{@code estimatedWaitSeconds} – 예상 대기 시간(초)</li>
 *   <li>{@code retryAfterSeconds}    – 클라이언트 폴링 주기 추천값 (동적 백오프)</li>
 * </ul>
 */
public record QueueStatusResponse(
        QueueStatus status,
        long rank,
        String activeToken,
        long estimatedWaitSeconds,
        double retryAfterSeconds
) {

    public static QueueStatusResponse waiting(long rank, long estimatedWaitSeconds, double retryAfterSeconds) {
        return new QueueStatusResponse(QueueStatus.WAITING, rank, null, estimatedWaitSeconds, retryAfterSeconds);
    }

    public static QueueStatusResponse admitted(String activeToken) {
        return new QueueStatusResponse(QueueStatus.ADMITTED, 0L, activeToken, 0L, 0.0);
    }

    public static QueueStatusResponse soldOut() {
        return new QueueStatusResponse(QueueStatus.SOLD_OUT, -1L, null, 0L, 0.0);
    }
}
