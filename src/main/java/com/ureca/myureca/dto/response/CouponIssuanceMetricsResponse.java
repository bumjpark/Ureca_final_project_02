package com.ureca.myureca.dto.response;

import java.util.List;

/**
 * 발급 현황 페이지의 실시간 그래프 + 보조 지표용 응답.
 * {@link CouponStatusResponse}(재고 기반 발급률)와는 별개로, coupon_issue 테이블 자체를
 * 집계해 상태별 건수와 초당 발급 추이를 보여준다.
 *
 * <p>{@code timeline}의 오른쪽 끝은 항상 "지금"이 아니라 마지막 발급 시각으로 고정된다 —
 * {@link com.ureca.myureca.service.CouponStatusService#getIssuanceMetrics} 참고.
 */
/**
 * @param redisElapsedMs Redis 발급(첫 issued_at ~ 마지막 issued_at) 충 소요 시간(ms). 발급 0건이면 null.
 * @param dbElapsedMs    전체 파이프라인 E2E(첫 issued_at ~ 마지막 created_at) 충 소요 시간(ms).
 *                       Redis 발급이 시작된 시점부터 DB에 마지막 INSERT가 끝난 시점까지의 전체 지연.
 *                       Redis 소요 시간보다 크면 Kafka Consumer 처리가 느린 것. 발급 0건이면 null.
 */
public record CouponIssuanceMetricsResponse(
        Long policyId,
        long totalIssuedEver,
        long usedCount,
        long expiredCount,
        long issuedLastSecond,
        List<IssuanceTimelinePointResponse> timeline,
        Long redisElapsedMs,
        Long dbElapsedMs
) {
}
