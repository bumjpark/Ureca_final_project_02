package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.IssueStatus;
import java.time.LocalDateTime;

/**
 * 내 쿠폰함의 쿠폰 1장.
 *
 * couponIssueId 는 상세 조회·이력 조회·사용 처리 API 가 모두 경로 변수로 받는 값이라
 * 목록에서 내려주지 않으면 클라이언트가 그 화면들로 넘어갈 수 없다.
 * receiptId 는 발급 응답(ACCEPTED)이 돌려준 접수 번호와 같은 값으로, 추적용으로 함께 준다.
 *
 * @param status        DB 에 저장된 원본 상태. {?status=} 필터도 이 값을 기준으로 동작한다.
 * @param displayStatus 화면 표시용 상태. ISSUED 인데 유효기간이 지났으면 EXPIRED 로 보정한다.
 *                      만료 배치가 아직 안 돌았어도 사용자에게는 만료로 보여야 하기 때문이다.
 * @param usable        지금 실제로 쓸 수 있는지
 * @param expiresAt     정책 마감 시각 = 이 쿠폰의 유효기간. null 이면 기한 없음
 */
public record MyCouponResponse(
        Long couponIssueId,
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
        return new MyCouponResponse(
                issue.getId(),
                issue.getReceiptId(),
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
                policy.getCloseAt()
        );
    }
}
