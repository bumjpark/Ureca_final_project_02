package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.coupon.CouponHistory;
import com.ureca.myureca.domain.coupon.HistoryPrevStatus;
import com.ureca.myureca.domain.coupon.IssueStatus;
import java.time.LocalDateTime;

/**
 * 쿠폰 상태 변경 결과.
 *
 * @param prevStatus 변경 전 상태. coupon_history 에서 오는 값이라 {@link HistoryPrevStatus} 를 쓴다.
 *                   상태 변경 경로에서는 NONE 이 나오지 않지만, 멱등 재생이 최초 발급 이력을
 *                   집어오는 경우에는 NONE 이 나올 수 있다 (Consumer 가 발급 이력을 NONE 으로 남긴다).
 * @param replayed   true 면 이번 요청으로 바뀐 게 아니라 같은 Idempotency-Key 의 이전 결과를 재생한 것이다.
 */
public record CouponUseResponse(
        Long couponIssueId,
        String receiptId,
        HistoryPrevStatus prevStatus,
        IssueStatus status,
        LocalDateTime usedAt,
        boolean replayed,
        String message
) {

    public static CouponUseResponse applied(Long couponIssueId, String receiptId,
                                            HistoryPrevStatus prevStatus, IssueStatus status,
                                            LocalDateTime usedAt) {
        return new CouponUseResponse(
                couponIssueId, receiptId, prevStatus, status, usedAt, false, messageOf(status));
    }

    /** 이미 처리된 요청의 결과 재생 */
    public static CouponUseResponse replayed(CouponHistory history) {
        return new CouponUseResponse(
                history.getCouponIssue().getId(),
                history.getCouponIssue().getReceiptId(),
                history.getPrevStatus(),
                history.getNewStatus(),
                history.getCouponIssue().getUsedAt(),
                true,
                "이미 처리된 요청입니다. 이전 처리 결과를 반환합니다.");
    }

    private static String messageOf(IssueStatus status) {
        return switch (status) {
            case USED -> "쿠폰을 사용 처리했습니다.";
            case ISSUED -> "쿠폰 사용을 취소했습니다.";
            case EXPIRED -> "쿠폰을 만료 처리했습니다.";
        };
    }
}
