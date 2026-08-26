package com.ureca.myureca.dto.response;

public record RedisRecoverResponse(
        Long policyId,
        String status,
        long kafkaLag,
        int totalQuantity,
        long issuedCount,
        int remainingStock
) {

    public static RedisRecoverResponse success(
            Long policyId, int totalQuantity, long issuedCount, int remainingStock, long kafkaLag) {
        return new RedisRecoverResponse(policyId, "SUCCESS", kafkaLag, totalQuantity, issuedCount, remainingStock);
    }
}
