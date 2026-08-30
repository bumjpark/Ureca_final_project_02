package com.ureca.myureca.service;

import com.ureca.myureca.repository.CouponIssueRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마감 기한(closeAt)이 지난 쿠폰의 DB 상태(status)를 ISSUED -> EXPIRED로 일괄 변경하는 서비스.
 *
 * <p>사용자 조회 및 사용 시점에는 isExpiredAt()을 통해 실시간 Lazy 처리가 이루어지지만,
 * 백오피스 통계 및 DB 무결성을 위해 주기적으로 실제 컬럼 상태를 동기화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponExpirationService {

    private final CouponIssueRepository couponIssueRepository;

    /**
     * 마감 일시(closeAt)가 현재 시각 이전인 모든 쿠폰 정책에 대해
     * ISSUED 상태인 발급 건을 EXPIRED 상태로 일괄 변경한다.
     *
     * @return 상태가 변경된 쿠폰 건수
     */
    @Transactional
    public int expireAllCoupons() {
        LocalDateTime now = LocalDateTime.now();
        int affected = couponIssueRepository.bulkExpireAllExpired(now);
        if (affected > 0) {
            log.info("만료 쿠폰 일괄 정리 완료: 총 {}건 EXPIRED 처리됨 (기준 시각={})", affected, now);
        }
        return affected;
    }

    /**
     * 특정 쿠폰 정책에 대해 ISSUED 상태인 발급 건을 EXPIRED 상태로 일괄 변경한다.
     *
     * @param policyId 쿠폰 정책 ID
     * @return 상태가 변경된 쿠폰 건수
     */
    @Transactional
    public int expireCouponsByPolicyId(Long policyId) {
        LocalDateTime now = LocalDateTime.now();
        int affected = couponIssueRepository.bulkExpireByPolicyId(policyId, now);
        if (affected > 0) {
            log.info("정책 ID {} 만료 쿠폰 일괄 정리 완료: {}건 EXPIRED 처리됨", policyId, affected);
        }
        return affected;
    }
}
