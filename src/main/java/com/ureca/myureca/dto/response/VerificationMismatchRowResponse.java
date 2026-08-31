package com.ureca.myureca.dto.response;

import java.time.LocalDateTime;

/**
 * 검증 불일치 CSV 한 줄. userId/couponIssueId는 정책 단위 요약 행(OVERSOLD, STOCK_LEAK)에서는
 * 특정 대상이 없어 둘 다 null이다.
 */
public record VerificationMismatchRowResponse(
        Long userId,
        Long couponIssueId,
        String discrepancyType,
        LocalDateTime detectedAt
) {
}
