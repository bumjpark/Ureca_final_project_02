package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.dto.response.VerificationReportResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.VerificationDispatchException;
import com.ureca.myureca.exception.VerificationNotAllowedException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정합성 검증 배치 오케스트레이터. 접근 조건을 확인하고 대상 정책마다 PENDING 리포트를
 * 저장한 뒤 실제 비교 작업은 VerificationAsyncTrigger에 맡긴다.
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final CouponPolicyRepository couponPolicyRepository;
    private final VerificationReportRepository verificationReportRepository;
    private final StringRedisTemplate redisTemplate;
    private final VerificationAsyncTrigger asyncTrigger;

    /** 목록 조회. policyId/status 둘 다 선택 필터이며, 최신 실행분이 먼저 오도록 정렬은 고정한다. */
    @Transactional(readOnly = true)
    public PageResponse<VerificationReportResponse> getVerificationReports(
            Long policyId, VerificationStatus status, Pageable pageable
    ) {
        Page<VerificationReport> page;
        if (policyId != null && status != null) {
            page = verificationReportRepository.findByCouponPolicy_IdAndStatusOrderByRunAtDesc(
                    policyId, status, pageable);
        } else if (policyId != null) {
            page = verificationReportRepository.findByCouponPolicy_IdOrderByRunAtDesc(policyId, pageable);
        } else if (status != null) {
            page = verificationReportRepository.findByStatusOrderByRunAtDesc(status, pageable);
        } else {
            page = verificationReportRepository.findAllByOrderByRunAtDesc(pageable);
        }
        return PageResponse.from(page.map(VerificationReportResponse::from));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<VerificationReportResponse> runVerification(Long policyId) {
        List<CouponPolicy> allPolicies = couponPolicyRepository.findByDeletedAtIsNull();
        validateNoPolicyHasRemainingStock(allPolicies);

        List<CouponPolicy> targets = (policyId != null)
                ? List.of(findAmong(allPolicies, policyId))
                : allPolicies;

        // 스트림 종단 연산(.toList())으로 한 번에 처리하면, 중간에 하나가 실패했을 때
        // 이미 접수된 정책이 몇 개인지 호출자가 알 방법이 없다 — for 루프로 풀어서
        // 실패 시점까지의 성공분을 예외에 담아 전달한다.
        List<VerificationReportResponse> dispatched = new ArrayList<>();
        for (CouponPolicy policy : targets) {
            try {
                dispatched.add(dispatch(policy));
            } catch (RuntimeException e) {
                List<Long> dispatchedIds = dispatched.stream().map(VerificationReportResponse::policyId).toList();
                throw new VerificationDispatchException(
                        "정책 id=" + policy.getId() + " 검증 접수 중 실패했습니다. "
                                + "이미 접수된 정책(재실행 불필요): " + dispatchedIds,
                        dispatchedIds,
                        e
                );
            }
        }
        return dispatched;
    }

    private VerificationReportResponse dispatch(CouponPolicy policy) {
        // 중복 디스패치 방지
        Optional<VerificationReport> existingPending = verificationReportRepository
                .findFirstByCouponPolicy_IdAndStatus(policy.getId(), VerificationStatus.PENDING);
        if (existingPending.isPresent()) {
            return VerificationReportResponse.from(existingPending.get());
        }

        VerificationReport pending = verificationReportRepository.save(
                VerificationReport.pending(policy, LocalDateTime.now(ZONE))
        );
        asyncTrigger.execute(pending.getId());
        return VerificationReportResponse.from(pending);
    }

    /** 접근 조건: 전체 정책 중 재고가 남아있는 정책이 하나라도 있으면 실행 자체를 거부한다. */
    private void validateNoPolicyHasRemainingStock(List<CouponPolicy> policies) {
        for (CouponPolicy policy : policies) {
            if (currentStock(policy.getId()) > 0) {
                throw new VerificationNotAllowedException(
                        "재고가 남아있는 정책(id=" + policy.getId()
                                + ")이 있어 검증 배치를 실행할 수 없습니다. 모든 정책의 재고가 소진된 뒤 실행해주세요.");
            }
        }
    }

    /** Redis 실시간 재고 카운터. 키가 없으면 Lua Fast-Fail과 동일하게 "재고 없음"으로 취급한다. */
    private long currentStock(Long policyId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.couponStock(policyId));
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private CouponPolicy findAmong(List<CouponPolicy> policies, Long policyId) {
        return policies.stream()
                .filter(p -> p.getId().equals(policyId))
                .findFirst()
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));
    }
}
