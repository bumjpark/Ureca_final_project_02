package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.dto.response.CouponDetailResponse;
import com.ureca.myureca.dto.response.MaskedUserResponse;
import com.ureca.myureca.dto.response.MyCouponPageResponse;
import com.ureca.myureca.dto.response.MyCouponResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.exception.CouponNotOwnedException;
import com.ureca.myureca.exception.UserNotFoundException;
import com.ureca.myureca.repository.CouponIssueRepository;
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
 * 내 쿠폰함 조회. 읽기 전용이며 Redis 를 거치지 않고 DB(진실의 원천)만 본다.
 *
 * 발급 경로는 Redis 가 RESERVED 를 들고 있다가 Kafka Consumer 가 DB 에 확정하는 비동기 구조라,
 * 발급 직후 짧은 시간 동안은 여기에 아직 안 보일 수 있다. 이건 버그가 아니라 설계상의 결과다.
 * "접수됐는가"는 발급 응답(202 ACCEPTED)이 답하고, 이 API 는 확정된 사실만 보여준다.
 *
 * {open-in-view: false} 이므로 DTO 변환을 이 트랜잭션 안에서 끝낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyCouponQueryService {

    private final CouponIssueRepository couponIssueRepository;
    private final UserRepository userRepository;

    public MyCouponPageResponse getMyCoupons(Long userId, IssueStatus status,
                                             Long couponPolicyId, Pageable pageable) {
        // 존재 검증과 마스킹용 정보 조회를 한 번에 한다.
        // 이 검증이 없으면 오타난 userId 가 "빈 쿠폰함"으로 조용히 200 을 받는다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        Page<CouponIssue> issues = findIssues(userId, status, couponPolicyId, pageable);

        LocalDateTime now = LocalDateTime.now();
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

    private Page<CouponIssue> findIssues(Long userId, IssueStatus status,
                                         Long couponPolicyId, Pageable pageable) {
        if (couponPolicyId == null) {
            return (status == null)
                    ? couponIssueRepository.findByUserId(userId, pageable)
                    : couponIssueRepository.findByUserIdAndStatus(userId, status, pageable);
        }
        return (status == null)
                ? couponIssueRepository.findByUserIdAndCouponPolicyId(userId, couponPolicyId, pageable)
                : couponIssueRepository.findByUserIdAndCouponPolicyIdAndStatus(
                        userId, couponPolicyId, status, pageable);
    }
}
