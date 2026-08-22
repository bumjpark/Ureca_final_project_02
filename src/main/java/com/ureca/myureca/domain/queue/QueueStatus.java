package com.ureca.myureca.domain.queue;

/**
 * 대기열 상태.
 *
 * <ul>
 *   <li>{@code WAITING}  – 대기열에 등록됨. 폴링으로 순번을 확인해야 한다.</li>
 *   <li>{@code ADMITTED} – 입장 가능. activeToken이 함께 반환된다.</li>
 *   <li>{@code SOLD_OUT} – 대기 중 재고 소진으로 품절 종료됨.</li>
 * </ul>
 */
public enum QueueStatus {
    WAITING,
    ADMITTED,
    SOLD_OUT
}
