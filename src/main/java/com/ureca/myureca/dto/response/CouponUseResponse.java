package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.coupon.CouponHistory;
import com.ureca.myureca.domain.coupon.HistoryPrevStatus;
import com.ureca.myureca.domain.coupon.IssueStatus;
import java.time.LocalDateTime;

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
