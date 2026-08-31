package com.ureca.myureca.support;

public final class RedisKeys {

    private RedisKeys() {
    }

    public static String activeToken(String token) {
        return "active_token:" + token;
    }

    /** 활성 토큰 발급 유저 역방향 키 (중복 토큰 발급 방어용) */
    public static String activeUser(Long policyId, Long userId) {
        return "active_user:" + policyId + ":" + userId;
    }

    public static String couponStock(Long policyId) {
        return couponPolicyPrefix(policyId) + ":stock";
    }

    public static String couponReserved(Long policyId) {
        return couponPolicyPrefix(policyId) + ":reserved";
    }

    /**
     * 입장(admission)했지만 아직 {@code /issue}로 확정되지 않은 유저 ZSET. score = 입장 시각(epoch millis).
     *
     * <p>이 키의 목적은 재고를 미리 예약하는 게 아니다 — 재고 차감은 여전히 {@code issue_coupon.lua}
     * 한 곳에서만 일어난다. 대신 {@code QueueAdmissionScheduler}가 다음 배치 크기를 정할 때
     * {@code 재고 - 이 ZSET 크기}만큼만 입장시켜서, 아직 발급 확정 안 된 사람들과 새로 입장하는
     * 사람들이 같은 재고를 놓고 여러 틱에 걸쳐 겹쳐서 경쟁하는 것을 막는다(2026-08-30 FCFS 역전
     * 조사에서 확인한 원인 — 회차별 조사 기록: Docs/load-test 참고).
     *
     * <p>{@code /issue} 호출은 성공/실패와 무관하게 항상 이 ZSET에서 자신을 제거한다(터미널 상태
     * 진입). TTL 안에 {@code /issue}를 끝내 호출하지 않으면 {@code QueueAdmissionScheduler}가
     * 주기적으로 오래된 항목을 걷어낸다(재고를 되돌릴 필요는 없다 — 애초에 안 깎았으므로).
     */
    public static String couponPending(Long policyId) {
        return couponPolicyPrefix(policyId) + ":pending";
    }

    public static String couponIssued(Long policyId) {
        return couponPolicyPrefix(policyId) + ":issued";
    }

    /** Redis 재구성(완전 유실 복구, E) 중간 산출물. 다 채운 뒤 issued 키로 원자적 교체(RENAME)한다. */
    public static String couponIssuedStaging(Long policyId) {
        return couponPolicyPrefix(policyId) + ":issued:staging";
    }

    /** 대기열 ZSET: score = 진입 순번(seq), member = userId */
    public static String couponQueue(Long policyId) {
        return couponPolicyPrefix(policyId) + ":queue";
    }

    /** 대기열 단조 증가 시퀀스 카운터 (동일 ms 동시 진입 시 순번 왜곡 방지) */
    public static String couponQueueSeq(Long policyId) {
        return couponPolicyPrefix(policyId) + ":queue:seq";
    }

    /** 대기열 입장 스케줄러 분산 락 키 (다중 서버 중복 실행 방어용) */
    public static String lockAdmission(Long policyId) {
        return "lock:admission:" + policyId;
    }

    /** 인프라 상태 전환 알림 중복 발송 방지 락 키 (다중 서버 인스턴스 환경에서 한 번의 상태
     *  전환에 대해 알림을 한 번만 보내도록 조율). alertKind 예: "infra-down", "infra-up" */
    public static String lockAlert(String alertKind) {
        return "lock:alert:" + alertKind;
    }

    /** 정합성 재처리 자동 재시도 스케줄러 분산 락 키 (다중 서버 중복 실행 방어용, 이슈 #21).
     *  type별로 분리해 EVENT_REPUBLISH/DLT_REPROCESS가 서로 다른 인스턴스에서 동시에 돌 수 있게 한다. */
    public static String lockReconciliationRetry(String type) {
        return "lock:reconciliation-retry:" + type;
    }

    /** 특정 정책의 동적 대기열 처리 Limit 키 */
    public static String queueLimit(Long policyId) {
        return "queue:limit:" + policyId;
    }

    /** 전체 정책에 적용되는 글로벌 기본 대기열 처리 Limit 키 */
    public static String queueDefaultLimit() {
        return "queue:limit:default";
    }

    /** 유저별 대기열 진입 Rate Limit 키 */
    public static String rateLimit(Long policyId, Long userId) {
        return "rate_limit:" + policyId + ":" + userId;
    }

    /** 대기열 입장 이력 마커 키 (토큰 만료 vs 미등록 식별용) */
    public static String admittedMarker(Long policyId, Long userId) {
        return "admitted_marker:" + policyId + ":" + userId;
    }

    private static String couponPolicyPrefix(Long policyId) {
        return "coupon:policy:" + policyId;
    }
}
