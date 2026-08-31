package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.dto.response.CouponPolicyExpirationResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마감 기한(closeAt)이 지난 쿠폰 정책 및 발급 쿠폰의 DB 상태를 EXPIRED 로 청크 단위로 안전하게 분할 변경하는 서비스.
 *
 * <p>수백만 건의 대용량 데이터 환경에서도 단일 트랜잭션의 락(Lock) 점유 및 Undo Log 폭발을 방지하기 위해,
 * 기본 5,000건 단위로 쪼개어 독립 커밋({@code REQUIRES_NEW})하며 순차 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponExpirationService {

    /** 기본 청크 크기 (한 번의 트랜잭션에서 처리 및 커밋할 레코드 수) */
    public static final int DEFAULT_CHUNK_SIZE = 5000;

    /** 청크 간 휴식 시간(ms) — DB CPU 및 I/O 여유 확보용 */
    public static final long CHUNK_PAUSE_MILLIS = 10L;

    private final CouponPolicyRepository couponPolicyRepository;
    private final CouponPolicyCacheService couponPolicyCacheService;
    private final CouponExpirationChunkExecutor chunkExecutor;

    /**
     * 특정 쿠폰 정책의 상태를 EXPIRED 로 변경하고, 소속된 ISSUED 쿠폰들을 청크 단위로 EXPIRED 로 변경한다.
     *
     * @param policyId  쿠폰 정책 ID
     * @param chunkSize 청크 크기 (0 이하일 경우 기본값 5,000 사용)
     * @return 상태가 변경된 총 쿠폰 건수 및 정책 정보 응답
     */
    public CouponPolicyExpirationResponse expireCouponsByPolicyId(Long policyId, int chunkSize) {
        int size = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        LocalDateTime now = LocalDateTime.now();

        // 1. 정책 상태를 EXPIRED 로 변경
        expirePolicyEntity(policyId);

        // 2. 발급 쿠폰들을 청크 단위로 EXPIRED 로 변경
        int totalAffected = 0;
        int chunkIndex = 1;

        log.info("정책 ID {} 만료 쿠폰 청크 처리 시작 (청크 크기={})", policyId, size);

        while (true) {
            int affected = chunkExecutor.expireChunk(policyId, now, size);
            totalAffected += affected;

            if (affected < size) {
                // 남은 대상이 청크 크기보다 작으면 모든 대상 처리가 끝났음을 의미
                break;
            }

            chunkIndex++;
            pauseShort();
        }

        if (totalAffected > 0) {
            log.info("정책 ID {} 만료 쿠폰 청크 처리 완료: 총 {}건 ({}회 분할 커밋)", policyId, totalAffected, chunkIndex);
        }

        // 3. 캐시 무효화
        couponPolicyCacheService.evict(policyId);

        return new CouponPolicyExpirationResponse(
                policyId,
                1,
                totalAffected,
                "정책 ID %d 및 소속 쿠폰 %d건이 정상적으로 만료(EXPIRED) 처리되었습니다.".formatted(policyId, totalAffected)
        );
    }

    /**
     * 기본 청크 크기(5,000건)로 특정 정책의 쿠폰을 만료 처리한다.
     */
    public CouponPolicyExpirationResponse expireCouponsByPolicyId(Long policyId) {
        return expireCouponsByPolicyId(policyId, DEFAULT_CHUNK_SIZE);
    }

    /**
     * 마감 일시(closeAt)가 지난 모든 쿠폰 정책을 탐색하여 정책 상태 및 쿠폰들을 일괄 만료 처리한다.
     *
     * @param chunkSize 청크 크기
     * @return 총 처리된 정책 수 및 쿠폰 수 응답
     */
    public CouponPolicyExpirationResponse expireAllCoupons(int chunkSize) {
        LocalDateTime now = LocalDateTime.now();
        List<CouponPolicy> expiredPolicies = couponPolicyRepository.findExpiredPolicies(now);
        int totalAffectedCoupons = 0;

        for (CouponPolicy policy : expiredPolicies) {
            CouponPolicyExpirationResponse res = expireCouponsByPolicyId(policy.getId(), chunkSize);
            totalAffectedCoupons += res.affectedCoupons();
        }

        if (!expiredPolicies.isEmpty()) {
            log.info("전체 만료 정책 청크 정리 완료: 대상 정책 수={}, 총 만료 쿠폰 수={}",
                    expiredPolicies.size(), totalAffectedCoupons);
        }

        return new CouponPolicyExpirationResponse(
                null,
                expiredPolicies.size(),
                totalAffectedCoupons,
                "총 %d개 정책 및 %d건의 쿠폰이 정상적으로 만료(EXPIRED) 처리되었습니다."
                        .formatted(expiredPolicies.size(), totalAffectedCoupons)
        );
    }

    /**
     * 기본 청크 크기(5,000건)로 모든 만료 정책의 쿠폰을 만료 처리한다.
     */
    public CouponPolicyExpirationResponse expireAllCoupons() {
        return expireAllCoupons(DEFAULT_CHUNK_SIZE);
    }

    @Transactional
    public void expirePolicyEntity(Long policyId) {
        CouponPolicy policy = couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));
        policy.expire();
    }

    private void pauseShort() {
        try {
            Thread.sleep(CHUNK_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
