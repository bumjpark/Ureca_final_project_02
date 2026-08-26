package com.ureca.myureca.controller;

import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.dto.response.MyCouponPageResponse;
import com.ureca.myureca.service.MyCouponQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/coupons")
@RequiredArgsConstructor
public class MyCouponController {

    /** 클라이언트가 size=100000 을 보내 DB 를 통째로 끌어가는 것을 막는 상한 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final MyCouponQueryService myCouponQueryService;

    /**
     * 내 쿠폰함 조회.
     *
     * GET /api/users/1/coupons
     * GET /api/users/1/coupons?status=ISSUED&amp;page=0&amp;size=20
     * GET /api/users/1/coupons?couponPolicyId=10          <- 1인 1매 검증
     */
    @GetMapping
    public MyCouponPageResponse getMyCoupons(
            @PathVariable Long userId,
            @RequestParam(required = false) IssueStatus status,
            @RequestParam(required = false) Long couponPolicyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        return myCouponQueryService.getMyCoupons(userId, status, couponPolicyId, toPageable(page, size));
    }

    /** 최신 발급 순 고정 정렬. 정렬 키를 클라이언트가 정하게 두면 인덱스를 못 타는 정렬이 들어온다. */
    private Pageable toPageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "issuedAt"));
    }
}
