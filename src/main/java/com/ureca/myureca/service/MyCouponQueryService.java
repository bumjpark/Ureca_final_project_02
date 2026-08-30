package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationStatus;
import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.dto.response.CouponDetailResponse;
import com.ureca.myureca.dto.response.CouponIssueStatusResponse;
import com.ureca.myureca.dto.response.MaskedUserResponse;
import com.ureca.myureca.dto.response.MyCouponPageResponse;
import com.ureca.myureca.dto.response.MyCouponResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.exception.CouponNotOwnedException;
import com.ureca.myureca.exception.UserNotFoundException;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import com.ureca.myureca.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 쿠폰함 조회 전용 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyCouponQueryService {

    private final UserRepository userRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final ReconciliationLogRepository reconciliationLogRepository;

    public MyCouponPageResponse getMyCoupons(Long userId, IssueStatus status,
                                             Long couponPolicyId, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        LocalDateTime now = LocalDateTime.now();
        Page<CouponIssue> issues = findIssues(userId, status, couponPolicyId, pageable, now);

        List<MyCouponResponse> coupons = issues.getContent().stream()
                .map(issue -> MyCouponResponse.from(issue, now))
                .toList();

        return new MyCouponPageResponse(
                MaskedUserResponse.from(user),
                coupons,
                MyCouponPageResponse.PageInfo.from(issues)
        );
    }

    public CouponDetailResponse getCouponDetail(Long couponIssueId, Long userId) {
        CouponIssue issue = couponIssueRepository.findDetailById(couponIssueId)
                .orElseThrow(() -> new CouponIssueNotFoundException(couponIssueId));

        if (!issue.getUser().getId().equals(userId)) {
            log.warn("타인 쿠폰 조회 시도. couponIssueId={}, requestUserId={}", couponIssueId, userId);
            throw new CouponNotOwnedException(couponIssueId);
        }

        return CouponDetailResponse.from(issue, LocalDateTime.now());
    }

    public CouponIssueStatusResponse getIssueStatusByReceiptId(String receiptId) {
        return couponIssueRepository.findByReceiptId(receiptId)
                .map(issue -> CouponIssueStatusResponse.issued(
                        receiptId, CouponDetailResponse.from(issue, LocalDateTime.now())))
                .orElseGet(() -> reconciliationLogRepository.findByEventKey(receiptId)
                        .filter(log -> log.getStatus() != ReconciliationStatus.SUCCESS)
                        .map(log -> CouponIssueStatusResponse.failed(receiptId, log.getFailReason()))
                        .orElseGet(() -> CouponIssueStatusResponse.pending(receiptId)));
    }

    private Page<CouponIssue> findIssues(Long userId, IssueStatus status,
                                         Long couponPolicyId, Pageable pageable, LocalDateTime now) {
        if (couponPolicyId == null) {
            if (status == null) {
                return couponIssueRepository.findByUserId(userId, pageable);
            }
            return switch (status) {
                case EXPIRED -> couponIssueRepository.findByUserIdAndEffectiveStatusExpired(userId, now, pageable);
                case ISSUED  -> couponIssueRepository.findByUserIdAndEffectiveStatusIssued(userId, now, pageable);
                case USED    -> couponIssueRepository.findByUserIdAndStatus(userId, status, pageable);
            };
        }
        if (status == null) {
            return couponIssueRepository.findByUserIdAndCouponPolicyId(userId, couponPolicyId, pageable);
        }
        return switch (status) {
            case EXPIRED -> couponIssueRepository.findByUserIdAndCouponPolicyIdAndEffectiveStatusExpired(
                    userId, couponPolicyId, now, pageable);
            case ISSUED  -> couponIssueRepository.findByUserIdAndCouponPolicyIdAndEffectiveStatusIssued(
                    userId, couponPolicyId, now, pageable);
            case USED    -> couponIssueRepository.findByUserIdAndCouponPolicyIdAndStatus(
                    userId, couponPolicyId, status, pageable);
        };
    }
}
