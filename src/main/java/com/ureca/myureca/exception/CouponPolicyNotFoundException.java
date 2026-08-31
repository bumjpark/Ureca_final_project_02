package com.ureca.myureca.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 존재하지 않는 coupon_policy_id로 발급현황을 조회했을 때 발생.
 *
 * 다른 예외들과 달리 GlobalExceptionHandler에서 별도로 매핑하지 않고
 * {@code @ResponseStatus}로 자체 완결시켰다. 다른 팀원들 PR의
 * exception/ErrorResponse.java가 서로 다른 필드 구조라 아직 병합 전이라
 * 그쪽에 의존하지 않기 위함 — 병합 후 팀 컨벤션이 정해지면 GlobalExceptionHandler로 옮겨도 된다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CouponPolicyNotFoundException extends RuntimeException {

    public CouponPolicyNotFoundException(Long policyId) {
        super("존재하지 않는 쿠폰 정책입니다. policyId=" + policyId);
    }
}
