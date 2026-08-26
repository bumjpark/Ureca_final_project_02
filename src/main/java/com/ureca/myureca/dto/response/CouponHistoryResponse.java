package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.coupon.CouponHistory;
import com.ureca.myureca.domain.coupon.IssueStatus;
import java.time.LocalDateTime;

public record CouponHistoryResponse(
        IssueStatus prevStatus,
        IssueStatus newStatus,
        String cancelReason,
        String requestId,
        LocalDateTime createdAt
) {
    public static CouponHistoryResponse from(CouponHistory history) {
        return new CouponHistoryResponse(
                history.getPrevStatus(),
                history.getNewStatus(),
                history.getCancelReason(),
                history.getRequestId(),
                history.getCreatedAt()
        );
    }
}
