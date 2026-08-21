package com.ureca.myureca.support;

public final class RedisKeys {

    private RedisKeys() {
    }

    public static String activeToken(String token) {
        return "active_token:" + token;
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

    private static String couponPolicyPrefix(Long policyId) {
        return "coupon:policy:" + policyId;
    }
}
