package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.RedisRecoverResponse;
import com.ureca.myureca.service.RedisRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redis 완전 유실 복구 (E). {@code POST /api/coupons/{eventId}/recover}
 *
 * <p><b>경로 네이밍 관련 참고</b>: 팀 아키텍처 로드맵 문서에 나온 경로
 * ({@code /api/coupons/{eventId}/recover})를 그대로 따랐다. 다만 이 프로젝트의 기존
 * 컨트롤러들은 "쿠폰 정책"을 {@code /api/coupon-policies/{policyId}/...} 로 부르고 있어서
 * ({@code CouponIssueController} 등 참고), 로드맵 문서의 {@code coupons}/{@code eventId}
 * 네이밍과는 결이 다르다. 실제 merge 전에 팀과 경로 네이밍(coupons vs coupon-policies,
 * eventId vs policyId)을 한 번 맞추는 게 좋다 — 여기서는 로드맵 문서 표기를 그대로 적용했다.</p>
 *
 * <p><b>=== 팀원의 코드가 따로필요! ===</b> 관리자 인증/인가 아직 없음.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupons")
public class RedisRecoveryController {

    private final RedisRecoveryService redisRecoveryService;

    @PostMapping("/{eventId}/recover")
    public ResponseEntity<RedisRecoverResponse> recover(@PathVariable Long eventId) {
        RedisRecoverResponse response = redisRecoveryService.recover(eventId);
        return ResponseEntity.ok(response);
    }
}
