package com.ureca.myureca.dto;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.IssueStatus;
import java.time.LocalDateTime;

/**
 * 내 쿠폰함의 쿠폰 1장.
 *
 * PK 대신 receiptId 를 노출한다. 발급 응답(ACCEPTED)이 돌려준 접수 번호와 같은 값이라
 * 클라이언트가 "내가 접수한 그 건"을 이어서 추적할 수 있다.
 *
 * @param status        DB 에 저장된 원본 상태. {?status=} 필터도 이 값을 기준으로 동작한다.
 * @param displayStatus 화면 표시용 상태. ISSUED 인데 유효기간이 지났으면 EXPIRED 로 보정한다.
 *                      만료 배치가 아직 안 돌았어도 사용자에게는 만료로 보여야 하기 때문이다.
 * @param usable        지금 실제로 쓸 수 있는지
 * @param expiresAt     정책 마감 시각 = 이 쿠폰의 유효기간. null 이면 기한 없음
 */
public record MyCouponResponse(
        String receiptId,
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
        LocalDateTime expiresAt
) {

    public static MyCouponResponse from(CouponIssue issue, LocalDateTime now) {
        CouponPolicy policy = issue.getCouponPolicy();
        boolean expired = isExpired(issue, now);
        return new MyCouponResponse(
                issue.getReceiptId(),
                policy.getId(),
                policy.getTitle(),
                policy.getCouponType(),
                policy.getDiscountValue(),
                discountLabel(policy),
                issue.getStatus(),
                expired ? IssueStatus.EXPIRED : issue.getStatus(),
                issue.getStatus() == IssueStatus.ISSUED && !expired,
                issue.getIssuedAt(),
                issue.getUsedAt(),
                policy.getCloseAt()
        );
    }

    /** close_at 은 NULL 을 허용한다. NULL 은 마감이 없다는 뜻이므로 만료되지 않는다. */
    private static boolean isExpired(CouponIssue issue, LocalDateTime now) {
        if (issue.getStatus() != IssueStatus.ISSUED) {
            return false;
        }
        LocalDateTime closeAt = issue.getCouponPolicy().getCloseAt();
        return closeAt != null && closeAt.isBefore(now);
    }

    private static String discountLabel(CouponPolicy policy) {
        return switch (policy.getCouponType()) {
            case FIXED -> String.format("%,d원 할인", policy.getDiscountValue());
            case RATE -> policy.getDiscountValue() + "% 할인";
        };
    }
}
