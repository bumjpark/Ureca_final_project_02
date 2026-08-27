package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponHistory;
import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.dto.request.CouponUseRequest;
import com.ureca.myureca.dto.response.CouponUseResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.exception.CouponNotOwnedException;
import com.ureca.myureca.exception.CouponStatusConflictException;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponIssueRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponUseService {

    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final CouponIssueRepository couponIssueRepository;
    private final CouponHistoryRepository couponHistoryRepository;

    @Transactional
    public CouponUseResponse changeStatus(Long couponIssueId, String requestId, CouponUseRequest request) {
        validateRequestId(requestId);

        // 1. 이미 반영된 요청이면 다시 실행하지 않고 그때의 결과를 그대로 돌려준다 (FR-13)
        Optional<CouponHistory> processed = couponHistoryRepository.findByRequestId(requestId);
        if (processed.isPresent()) {
            CouponHistory history = processed.get();
            requireSameRequest(history, couponIssueId, request);
            log.info("멱등 재요청 — 재실행하지 않음. couponIssueId={}, requestId={}", couponIssueId, requestId);
            return CouponUseResponse.replayed(history);
        }

        CouponIssue issue = couponIssueRepository.findDetailById(couponIssueId)
                .orElseThrow(() -> new CouponIssueNotFoundException(couponIssueId));

        // 2. 소유자 검증. 로그인이 없으므로(FR-1) 요청 바디의 userId 가 유일한 근거다
        if (!issue.getUser().getId().equals(request.userId())) {
            log.warn("타인 쿠폰 상태 변경 시도. couponIssueId={}, requestUserId={}",
                    couponIssueId, request.userId());
            throw new CouponNotOwnedException(couponIssueId);
        }

        Transition transition = Transition.toward(request.status());
        LocalDateTime now = LocalDateTime.now();
        IssueStatus currentStatus = issue.getStatus();

        // 3. 읽은 값 기준 사전 검증. 정합성 보증이 아니라 "왜 안 되는지" 정확히 알려주기 위한 것이다.
        if (currentStatus == transition.to()) {
            throw new CouponStatusConflictException(
                    "이미 %s 상태인 쿠폰입니다.".formatted(currentStatus));
        }
        if (currentStatus != transition.from()) {
            throw new CouponStatusConflictException(
                    "현재 상태가 %s 라 %s 로 변경할 수 없습니다. (%s 상태에서만 가능합니다)"
                            .formatted(currentStatus, transition.to(), transition.from()));
        }
        if (transition == Transition.USE && isPastDue(issue, now)) {
            // 내 쿠폰함 조회가 displayStatus=EXPIRED 로 보여주는 쿠폰은 여기서도 사용을 막아야 한다.
            // 두 판정이 어긋나면 "만료라고 보이는데 사용은 되는" 모순이 생긴다.
            throw new CouponStatusConflictException("유효기간이 지난 쿠폰입니다. 사용할 수 없습니다.");
        }

        String receiptId = issue.getReceiptId();
        LocalDateTime usedAt = transition.usedAtOf(now);

        // 4. 조건부 UPDATE — 동시 요청 중 정확히 하나만 1을 받는다
        int affected = couponIssueRepository.updateStatusIf(
                couponIssueId, transition.from(), transition.to(), usedAt, now);
        if (affected == 0) {
            throw new CouponStatusConflictException(
                    "다른 요청이 먼저 상태를 변경했습니다. 쿠폰을 다시 조회한 뒤 시도해주세요.");
        }

        // 5. 감사 로그. UNIQUE(request_id) 가 동시 중복 요청의 최후 방어선이다.
        //    벌크 UPDATE 가 영속성 컨텍스트를 비웠으므로 FK 용 참조를 새로 얻는다.
        try {
            couponHistoryRepository.saveAndFlush(new CouponHistory(
                    couponIssueRepository.getReferenceById(couponIssueId),
                    requestId,
                    transition.from(),
                    transition.to(),
                    request.reason()));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 요청이 동시에 들어와 여기까지 온 경우. 트랜잭션이 롤백되므로 상태 변경도 취소된다.
            throw new CouponStatusConflictException(
                    "동일한 Idempotency-Key 요청이 처리 중입니다. 잠시 후 다시 조회해주세요.");
        }

        log.info("쿠폰 상태 변경. couponIssueId={}, {} -> {}, requestId={}",
                couponIssueId, transition.from(), transition.to(), requestId);

        return CouponUseResponse.applied(
                couponIssueId, receiptId, transition.from(), transition.to(), usedAt);
    }

    private void requireSameRequest(CouponHistory history, Long couponIssueId, CouponUseRequest request) {
        CouponIssue target = history.getCouponIssue();
        boolean sameRequest = target.getId().equals(couponIssueId)
                && target.getUser().getId().equals(request.userId())
                && history.getNewStatus() == request.status();

        if (!sameRequest) {
            log.warn("Idempotency-Key 재사용 — 요청 내용이 다르다. requestId={}, 기록=[couponIssueId={}, {} -> {}], "
                            + "요청=[couponIssueId={}, status={}]",
                    history.getRequestId(), target.getId(), history.getPrevStatus(), history.getNewStatus(),
                    couponIssueId, request.status());
            throw new CouponStatusConflictException(
                    "이미 다른 요청에 사용된 Idempotency-Key 입니다. 새 키로 요청해주세요.");
        }
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 헤더는 필수입니다.");
        }
        if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key 는 " + MAX_REQUEST_ID_LENGTH + "자를 넘을 수 없습니다.");
        }
    }

    /** close_at 은 NULL 을 허용한다. NULL 이면 기한이 없다는 뜻이므로 만료되지 않는다. */
    private boolean isPastDue(CouponIssue issue, LocalDateTime now) {
        LocalDateTime closeAt = issue.getCouponPolicy().getCloseAt();
        return closeAt != null && closeAt.isBefore(now);
    }

    private enum Transition {

        /** 사용 처리 */
        USE(IssueStatus.ISSUED, IssueStatus.USED),
        /** 사용 취소 — used_at 은 NULL 로 되돌린다 */
        CANCEL_USE(IssueStatus.USED, IssueStatus.ISSUED),
        /** 만료 처리 */
        EXPIRE(IssueStatus.ISSUED, IssueStatus.EXPIRED);

        private final IssueStatus from;
        private final IssueStatus to;

        Transition(IssueStatus from, IssueStatus to) {
            this.from = from;
            this.to = to;
        }

        static Transition toward(IssueStatus target) {
            return switch (target) {
                case USED -> USE;
                case ISSUED -> CANCEL_USE;
                case EXPIRED -> EXPIRE;
            };
        }

        IssueStatus from() {
            return from;
        }

        IssueStatus to() {
            return to;
        }

        /** 사용 처리만 used_at 을 남기고, 사용 취소·만료는 NULL 로 되돌린다 */
        LocalDateTime usedAtOf(LocalDateTime now) {
            return this == USE ? now : null;
        }
    }
}
