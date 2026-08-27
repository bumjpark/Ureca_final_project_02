package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.IssueStatus;
import java.time.LocalDateTime;

public record CouponDetailResponse(
        Long couponIssueId,
        String receiptId,
        MaskedUserResponse user,
        Long couponPolicyId,
        String title,
        CouponType couponType,
        Integer discountValue,
        String discountLabel,
        IssueStatus status,
        IssueStatus displayStatus,
        boolean usable,
        LocalDateTime issuedAt,
        LocalDateTime usedAt,
        LocalDateTime openAt,
        LocalDateTime expiresAt
) {

    public static CouponDetailResponse from(CouponIssue issue, LocalDateTime now) {
        CouponPolicy policy = issue.getCouponPolicy();
        return new CouponDetailResponse(
                issue.getId(),
                issue.getReceiptId(),
                MaskedUserResponse.from(issue.getUser()),
                policy.getId(),
                policy.getTitle(),
                policy.getCouponType(),
                policy.getDiscountValue(),
                CouponDiscountLabel.of(policy),
                issue.getStatus(),
                issue.isExpiredAt(now) ? IssueStatus.EXPIRED : issue.getStatus(),
                issue.isUsableAt(now),
                issue.getIssuedAt(),
                issue.getUsedAt(),
                policy.getOpenAt(),
                policy.getCloseAt()
        );
    }
}
