package com.ureca.myureca.dto.response;

import java.util.List;

/**
 * 대규모(300만 건) 정합성 검증 데모용 시딩/삭제/상태 응답. 관리자 화면에서 "딸깍"으로
 * 시딩·삭제·검증 결과 요약을 보기 위한 도구 — {@link com.ureca.myureca.service.ScaleTestService}
 * 참고.
 */
public record ScaleTestResponse(
        List<ScenarioResult> scenarios,
        long totalCouponIssueRows,
        long totalUsersSeeded
) {
    public record ScenarioResult(
            Long policyId,
            String title,
            String scenarioType,
            String scenarioDescription,
            Integer totalQuantity,
            long couponIssueRows,
            /** 시딩 직후에는 null. {@code /verify-all} 호출 이후에만 채워진다. */
            String verificationStatus,
            Integer mismatchCount
    ) {
    }
}
