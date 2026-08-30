package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.dto.response.CouponIssuanceMetricsResponse;
import com.ureca.myureca.dto.response.CouponStatusResponse;
import com.ureca.myureca.dto.response.IssuanceTimelinePointResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponStatusService {

    private static final DateTimeFormatter SECOND_BUCKET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CouponPolicyRepository couponPolicyRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final StringRedisTemplate redisTemplate;

    @Transactional(readOnly = true)
    public CouponStatusResponse getCouponStatus(Long policyId) {
        // 1. DB에서 쿠폰 정책 조회 (소프트 삭제된 정책 제외)
        CouponPolicy couponPolicy = couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        int totalQuantity = couponPolicy.getTotalQuantity();

        // 2. Redis에서 실시간 재고 조회 (UBM-14 키 규격과 일치)
        String stockValue = redisTemplate.opsForValue().get(RedisKeys.couponStock(policyId));

        // Redis에 재고 키가 없으면 기본 총 수량으로 방어
        int remainingQuantity = (stockValue != null) ? Integer.parseInt(stockValue) : totalQuantity;

        // 초과 발급 방어 (화면 표기용 음수 방지).
        // 정상 운영에선 Lua의 Fast-Fail 덕분에 절대 음수가 나오면 안 되므로,
        // 이 분기를 탄다는 것 자체가 NFR-1(초과발급 0건) 위반 신호다 — 화면은 0으로
        // 보여주되 반드시 경고 로그를 남겨 검증배치가 추적할 수 있게 한다.
        if (remainingQuantity < 0) {
            log.warn("Redis stock이 음수입니다 — 초과발급 의심. policyId={}, stock={}", policyId, remainingQuantity);
            remainingQuantity = 0;
        }

        // 3. 발급 완료 수량 계산
        int issuedQuantity = totalQuantity - remainingQuantity;

        // 4. 발급률 계산 (소수점 둘째 자리까지 반올림)
        double issueRate = (totalQuantity == 0)
                ? 0.0
                : Math.round(((double) issuedQuantity / totalQuantity) * 10000.0) / 100.0;

        return new CouponStatusResponse(
                policyId,
                totalQuantity,
                issuedQuantity,
                remainingQuantity,
                issueRate);
    }

    /**
     * 발급 현황 페이지의 실시간 그래프 + 보조 지표. coupon_issue 테이블 자체를 집계하므로
     * Redis 재고 기반의 {@link #getCouponStatus}와는 별개 관점(사용/만료 건수, 초당 발급 추이)이다.
     *
     * <p>그래프 창의 끝(오른쪽 끝)은 항상 "지금"이 아니라 <b>마지막 발급 시각</b>으로 고정한다 —
     * 끝을 "지금"으로 두면 발급이 멎은 뒤에도 매 폴링마다 빈 구간이 계속 밀려 들어오면서 그래프가
     * 끝없이 스크롤되는 것처럼 보인다. 새 발급이 없는 한 같은 창을 그대로 돌려줘서, 화면이
     * 실제로 무슨 일이 있었을 때만 갱신되게 한다.
     *
     * @param seconds 그래프 창(초). 정책 전체 이력을 스캔하지 않도록 이 범위만 조회한다.
     */
    @Transactional(readOnly = true)
    public CouponIssuanceMetricsResponse getIssuanceMetrics(Long policyId, int seconds) {
        couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        long usedCount = 0;
        long expiredCount = 0;
        long activeIssuedCount = 0;
        for (Object[] row : couponIssueRepository.countByStatusGroupedForPolicy(policyId)) {
            IssueStatus status = (IssueStatus) row[0];
            long count = (Long) row[1];
            switch (status) {
                case USED -> usedCount = count;
                case EXPIRED -> expiredCount = count;
                case ISSUED -> activeIssuedCount = count;
            }
        }
        long totalIssuedEver = activeIssuedCount + usedCount + expiredCount;

        LocalDateTime now = LocalDateTime.now();
        long issuedLastSecond = couponIssueRepository.countIssuedSince(policyId, now.minusSeconds(1));

        LocalDateTime lastIssuedAt = couponIssueRepository.findMaxIssuedAtByCouponPolicyId(policyId);
        // 발급 이력이 아예 없으면 고정시킬 기준이 없으니 지금 시각으로 채운다(항상 전부 0).
        LocalDateTime windowEnd = (lastIssuedAt != null) ? lastIssuedAt.withNano(0) : now.withNano(0);

        // 그래프에 빈 구간(발급이 없던 초)도 0으로 표시되도록 창 전체를 먼저 0으로 채워둔다.
        LocalDateTime since = windowEnd.minusSeconds(seconds);
        Map<String, Long> countsByBucket = new LinkedHashMap<>();
        for (LocalDateTime cursor = since; !cursor.isAfter(windowEnd); cursor = cursor.plusSeconds(1)) {
            countsByBucket.put(cursor.format(SECOND_BUCKET_FORMAT), 0L);
        }
        for (Object[] row : couponIssueRepository.countIssuedBySecondSince(policyId, since)) {
            String bucket = (String) row[0];
            long count = ((Number) row[1]).longValue();
            countsByBucket.put(bucket, count);
        }

        List<IssuanceTimelinePointResponse> timeline = countsByBucket.entrySet().stream()
                .map(e -> new IssuanceTimelinePointResponse(e.getKey(), e.getValue()))
                .toList();

        return new CouponIssuanceMetricsResponse(
                policyId, totalIssuedEver, usedCount, expiredCount, issuedLastSecond, timeline);
    }
}
