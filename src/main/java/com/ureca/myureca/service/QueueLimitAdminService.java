package com.ureca.myureca.service;

import com.ureca.myureca.dto.request.QueueLimitUpdateRequest;
import com.ureca.myureca.dto.response.QueueAdminStatusResponse;
import com.ureca.myureca.dto.response.QueueLimitResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 대기열 초당 처리량(Limit)을 실시간으로 관리하고 스케줄러에 공급하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueLimitAdminService {

    private final StringRedisTemplate redisTemplate;
    private final CouponPolicyRepository couponPolicyRepository;

    /** application.yml 기본 fallback 초당 처리량 (기본값: 300) */
    @Value("${coupon.queue.admission-rate:300}")
    private int defaultAdmissionRate;

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 50000;

    /**
     * 대기열 처리 Limit을 실시간으로 변경한다.
     *
     * @param request 수정 요청 DTO (policyId가 null이면 글로벌 기본 Limit 수정)
     * @return 수정 결과 DTO
     */
    public QueueLimitResponse updateLimit(QueueLimitUpdateRequest request) {
        Long policyId = request.policyId();
        int limit = request.limit();

        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    String.format("대기열 Limit은 %d 이상 %d 이하여야 합니다. 입력값=%d", MIN_LIMIT, MAX_LIMIT, limit));
        }

        if (policyId != null) {
            // 정책 존재 여부 검증
            couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                    .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

            String key = RedisKeys.queueLimit(policyId);
            redisTemplate.opsForValue().set(key, String.valueOf(limit));
            log.info("쿠폰 정책별 대기열 Limit 변경 완료: policyId={}, limit={}", policyId, limit);
        } else {
            String key = RedisKeys.queueDefaultLimit();
            redisTemplate.opsForValue().set(key, String.valueOf(limit));
            log.info("글로벌 대기열 Limit 변경 완료: limit={}", limit);
        }

        return QueueLimitResponse.of(policyId, limit);
    }

    /**
     * 특정 정책의 현재 유효한 초당 처리 Limit을 계산하여 반환한다.
     *
     * <ol>
     *   <li>1순위: 정책별 개별 설정 Limit (Redis)</li>
     *   <li>2순위: 글로벌 기본 Limit (Redis)</li>
     *   <li>3순위: application.yml 기본값 (300)</li>
     * </ol>
     */
    public int getEffectiveLimit(Long policyId) {
        if (policyId != null) {
            String policyLimitStr = redisTemplate.opsForValue().get(RedisKeys.queueLimit(policyId));
            if (policyLimitStr != null) {
                try {
                    return Integer.parseInt(policyLimitStr);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String defaultLimitStr = redisTemplate.opsForValue().get(RedisKeys.queueDefaultLimit());
        if (defaultLimitStr != null) {
            try {
                return Integer.parseInt(defaultLimitStr);
            } catch (NumberFormatException ignored) {
            }
        }

        return defaultAdmissionRate;
    }

    /**
     * 지금 이 정책 대기열에 몇 명이 대기 중인지 + 현재 적용 중인 처리 속도를 함께 보여준다.
     * 부하테스트를 걸어놓고 대기열이 실제로 줄어드는지 화면에서 눈으로 확인하기 위한 조회 전용
     * 엔드포인트다(대기열 ZSET 크기 자체를 세므로 발급 성공 여부와 무관하게 "입장 대기 인원"을 뜻한다).
     */
    public QueueAdminStatusResponse getStatus(Long policyId) {
        couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        Long waitingCount = redisTemplate.opsForZSet().size(RedisKeys.couponQueue(policyId));
        boolean usingDefaultLimit = redisTemplate.opsForValue().get(RedisKeys.queueLimit(policyId)) == null;
        int currentLimit = getEffectiveLimit(policyId);

        return new QueueAdminStatusResponse(
                policyId, waitingCount != null ? waitingCount : 0L, currentLimit, usingDefaultLimit);
    }

    /**
     * 자동 스케일링이 한 틱에 허가할 수 있는 인원의 상한 비율(잔여 재고 대비 %).
     *
     * <p><b>왜 필요한가</b>: 입장 허가는 재고를 차감하지 않는다 — 실제 차감은 그 유저가 나중에
     * {@code /issue}를 호출할 때 일어난다. 그런데 입장 허가받은 유저는 자기 차례가 됐다는 걸
     * 다음 폴링에서야 알고, {@code QueueService.calculateRetryAfter}의 동적 백오프 때문에 그게
     * 최대 3초 뒤일 수 있다. 즉 입장 스케줄러는 매 1초 틱마다 <b>아직 반영되지 않은 재고 값</b>을
     * 보고 판단하게 되며, 최악의 경우 3틱 동안 같은 재고를 근거로 중복 허가한다. 한 틱의 배치가
     * 잔여 재고에 비해 클수록 이 구간에서 과다 입장이 커지고, 그 초과 인원 사이의 승부는 도착
     * 순서(seq)가 아니라 {@code /issue} 호출 타이밍이 갈라놓으므로 <b>선착순 순서가 깨진다</b>
     * (이슈 #8). 실측(2026-08-28, 재고 10,000 / admission-rate 2000 = 재고의 20%)에서 747명
     * 초과 허가 → FCFS 역전 356쌍이 발생했다.
     *
     * <p>5%로 잡은 근거: 같은 실측 시리즈에서 재고의 3~5% 수준(재고 10,000 기준 300~500)은
     * 역전 0건을 유지했다. 발급 개수·중복 방지 같은 안전 속성은 Lua 원자성이 항상 보장하므로,
     * 이 값이 지키는 건 순수하게 "선착순 순서"다.
     */
    private static final int MAX_ADMISSION_PERCENT_OF_STOCK = 5;

    /**
     * 대기열 부하 상태에 따라 처리 Limit을 자동 스케일링하되, 잔여 재고 대비 안전 상한을 넘지
     * 않도록 조인다.
     *
     * <ul>
     *   <li>대기열 인원이 5,000명 이상으로 급증 시: 기본값의 2배(최대 1,000)로 동적 확장</li>
     *   <li>대기열 인원이 10,000명 이상 폭증 시: 기본값의 3배(최대 2,000)로 추가 확장</li>
     *   <li>단, 위 확장분은 잔여 재고의 {@value #MAX_ADMISSION_PERCENT_OF_STOCK}%를 넘지 못한다</li>
     * </ul>
     *
     * <p><b>스케일 기준에 잔여 재고를 넣은 이유</b>: 예전에는 대기열 길이만 봤는데, 정작 이슈 #8의
     * 위험도를 결정하는 건 "재고 대비 배치 크기"다. 그래서 아무도 설정을 바꾸지 않아도 자동
     * 스케일링만으로 위험 구간에 진입할 수 있었다 — 기본값 300에서도 대기열 1만 명이면 900(재고
     * 10,000 기준 9%)이 되고, 운영자가 정책별 Limit을 700 이상으로 올려둔 상태라면 자동으로
     * 2,000(20%)까지 올라가 실측에서 FCFS 역전을 만든 바로 그 값에 도달한다. 지금까지 사고가 안
     * 난 건 대기열 동적 캡(재고×1.1)과 우연히 맞물린 결과지, 설계가 보장한 게 아니었다.
     *
     * <p>운영자가 명시적으로 설정한 {@code baseLimit} 자체는 이 상한이 깎지 않는다 — 자동
     * 스케일링(기계가 알아서 올리는 부분)만 조이는 것이 이 가드의 역할이고, baseLimit을 얼마로
     * 둘지는 여전히 운영자의 판단 영역이기 때문이다(권장: 재고의 3~5%).
     *
     * @param queueSize      현재 대기열 인원
     * @param remainingStock 현재 잔여 재고. 0 이하이거나 알 수 없으면(음수) 스케일링하지 않는다.
     */
    public int calculateAutoScaledLimit(Long policyId, long queueSize, long remainingStock) {
        int baseLimit = getEffectiveLimit(policyId);

        int scaledLimit;
        if (queueSize >= 10000) {
            scaledLimit = Math.min(2000, baseLimit * 3);
        } else if (queueSize >= 5000) {
            scaledLimit = Math.min(1000, baseLimit * 2);
        } else {
            return baseLimit;
        }

        if (remainingStock <= 0) {
            // 재고를 모르거나 이미 소진 — 확장할 근거가 없으므로 운영자 설정값 그대로 간다.
            return baseLimit;
        }

        int stockSafetyCap = (int) Math.min(
                Integer.MAX_VALUE, remainingStock * MAX_ADMISSION_PERCENT_OF_STOCK / 100);
        return Math.max(baseLimit, Math.min(scaledLimit, stockSafetyCap));
    }
}
