package com.ureca.myureca.dto.event;

/**
 * 대기열 통과(ADMITTED) 시 실시간 SSE 푸시를 위한 Redis Pub/Sub 메시지 (이슈 #23).
 *
 * <p>{@code QueueAdmissionService.admitUsers}를 실행한 인스턴스가 Redis 채널로 발행하면,
 * 모든 인스턴스가 이를 구독해 각자의 로컬 {@code emitterMap}(JVM 메모리, 인스턴스별로 다름)에
 * 이 유저가 실제로 연결돼 있는지 확인하고 있으면 push한다 — SSE 연결이 어느 인스턴스로
 * 라우팅됐는지와 무관하게 항상 push가 전달되도록 하기 위함이다.
 */
public record AdmittedPushEvent(
        Long policyId,
        Long userId,
        String activeToken
) {
}
