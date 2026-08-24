package com.ureca.myureca.service;

import com.ureca.myureca.dto.request.QueueLimitUpdateRequest;
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

    /**
     * 대기열 처리 Limit을 실시간으로 변경한다.
     *
     * @param request 수정 요청 DTO (policyId가 null이면 글로벌 기본 Limit 수정)
     * @return 수정 결과 DTO
     */
    public QueueLimitResponse updateLimit(QueueLimitUpdateRequest request) {
        Long policyId = request.policyId();
        int limit = request.limit();

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
     * 대기열 부하 상태에 따라 처리 Limit을 자동 스케일링한다.
     *
     * <ul>
     *   <li>대기열 인원이 5,000명 이상으로 급증 시: 기본값의 2배(최대 1,000)로 동적 확장</li>
     *   <li>대기열 인원이 10,000명 이상 폭증 시: 기본값의 3배(최대 2,000)로 추가 확장</li>
     * </ul>
     */
    public int calculateAutoScaledLimit(Long policyId, long queueSize) {
        int baseLimit = getEffectiveLimit(policyId);

        if (queueSize >= 10000) {
            return Math.min(2000, baseLimit * 3);
        } else if (queueSize >= 5000) {
            return Math.min(1000, baseLimit * 2);
        }
        return baseLimit;
    }
}
