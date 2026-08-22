package com.ureca.myureca.domain.queue;

/**
 * 대기열 진입 결과 상태.
 *
 * <ul>
 *   <li>{@code WAITING}  – 대기열에 등록됨. 폴링으로 상태를 확인해야 한다.</li>
 *   <li>{@code ADMITTED} – 대기 없이 즉시 입장 가능. activeToken이 함께 반환된다.</li>
 * </ul>
 */
public enum QueueStatus {
    WAITING,
    ADMITTED
}
