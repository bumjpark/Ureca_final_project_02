package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
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
    private final ReconciliationLogRepository reconciliationLogRepository;

    public MyCouponPageResponse getMyCoupons(Long userId, IssueStatus status,
                                             Long couponPolicyId, Pageable pageable) {
        // 존재 검증과 마스킹용 정보 조회를 한 번에 한다.
        // 이 검증이 없으면 오타난 userId 가 "빈 쿠폰함"으로 조용히 200 을 받는다.
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

    /**
     * 발급 접수(receiptId) 상태 조회. 202 ACCEPTED 직후 클라이언트가 곧바로 재조회했을 때
     * "DB에 아직 없음"을 "존재하지 않음"과 뭉개지 않고 세 가지로 구분한다 — 클래스 주석에
     * 적힌 비동기 반영 지연 자체는 설계상 정상이므로, 이 메서드는 그 창을 없애는 게 아니라
     * 그 창 안에서 클라이언트가 뭘 기대해야 하는지를 알려준다.
     */
    public CouponIssueStatusResponse getIssueStatusByReceiptId(String receiptId) {
        return couponIssueRepository.findByReceiptId(receiptId)
                .map(issue -> CouponIssueStatusResponse.issued(
                        receiptId, CouponDetailResponse.from(issue, LocalDateTime.now())))
                .orElseGet(() -> reconciliationLogRepository.findByEventKey(receiptId)
                        // SUCCESS면 재발행이 이미 성공해 Consumer가 곧 반영할 것이므로 PENDING과
                        // 동일하게 취급한다 — 재처리 자체의 실패가 아니라 아직 못 따라잡은 것뿐이다.
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
            // EXPIRED, ISSUED 는 DB 물리 상태만으로 판단할 수 없다 — closeAt 도 함께 본다.
            // USED 는 closeAt 과 무관하므로 기존 단순 쿼리를 그대로 사용한다.
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
