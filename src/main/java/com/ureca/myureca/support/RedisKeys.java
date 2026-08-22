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

    public static String couponIssued(Long policyId) {
        return couponPolicyPrefix(policyId) + ":issued";
    }

    /** 대기열 ZSET: score = 진입 순번(seq), member = userId */
    public static String couponQueue(Long policyId) {
        return couponPolicyPrefix(policyId) + ":queue";
    }

    /** 대기열 단조 증가 시퀀스 카운터 (동일 ms 동시 진입 시 순번 왜곡 방지) */
    public static String couponQueueSeq(Long policyId) {
        return couponPolicyPrefix(policyId) + ":queue:seq";
    }

    private static String couponPolicyPrefix(Long policyId) {
        return "coupon:policy:" + policyId;
    }
}
