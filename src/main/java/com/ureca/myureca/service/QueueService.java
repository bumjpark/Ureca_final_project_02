package com.ureca.myureca.service;

import com.ureca.myureca.domain.queue.QueueStatus;
import com.ureca.myureca.dto.request.QueueJoinRequest;
import com.ureca.myureca.dto.response.QueueJoinResponse;
import com.ureca.myureca.dto.response.QueueStatusResponse;
import com.ureca.myureca.exception.CouponDuplicatedException;
import com.ureca.myureca.exception.CouponNotOpenedException;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.CouponSoldOutException;
import com.ureca.myureca.exception.QueueFullException;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    /**
     * 대기열 최대 수용 인원.
     * 재고(10,000) × 여유 배수(3) = 30,000 기본값.
     * application.yml 에서 coupon.queue.max-size 로 오버라이드 가능.
     */
    @Value("${coupon.queue.max-size:30000}")
    private long maxQueueSize;

    /**
     * 토큰 유효 시간(초). 기본 60초.
     * application.yml 에서 coupon.queue.token-ttl-seconds 로 오버라이드 가능.
     */
    @Value("${coupon.queue.token-ttl-seconds:60}")
    private long tokenTtlSeconds;

    /**
     * 1명당 예상 처리 시간(초). 대기 예상 시간 계산에 사용.
     */
    private static final long ESTIMATED_SECONDS_PER_PERSON = 1L;

    // Lua 스크립트 반환 상태 코드
    private static final long LUA_WAITING = 200L;
    private static final long LUA_ADMITTED = 201L;
    private static final long LUA_DUPLICATED = 409L;
    private static final long LUA_SOLD_OUT = 400L;
    private static final long LUA_QUEUE_FULL = 503L;
    private static final long LUA_NOT_INITIALIZED = 500L;

    private final CouponPolicyCacheService couponPolicyCacheService;
    private final QueueRateLimiter queueRateLimiter;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> joinQueueScript;
    private final RedisScript<List<String>> getQueueStatusScript;

    /**
     * 대기열 등록 메인 로직.
     */
    public QueueJoinResponse joinQueue(QueueJoinRequest request) {
        Long policyId = request.policyId();
        Long userId = request.userId();

        // 1. 유저별 1초당 요청 제한 (매크로/연타 429 차단)
        queueRateLimiter.checkRateLimit(policyId, userId);

        // 2. 정책 유효성 검증 (In-Memory 캐시 조회로 DB 부하 0)
        validatePolicy(policyId);

        // 3. Redis Lua 원자적 실행
        List<String> keys = List.of(
                RedisKeys.couponIssued(policyId),
                RedisKeys.couponReserved(policyId),
                RedisKeys.couponStock(policyId),
                RedisKeys.couponQueue(policyId),
                RedisKeys.couponQueueSeq(policyId),
                RedisKeys.activeUser(policyId, userId)
        );

        List<Long> result;
        try {
            result = redisTemplate.execute(
                    joinQueueScript,
                    keys,
                    String.valueOf(userId),
                    String.valueOf(maxQueueSize)
            );
        } catch (Exception e) {
            log.error("Redis 대기열 진입 실패 (일시 장애/지연). policyId={}, userId={}", policyId, userId, e);
            throw new QueueFullException(policyId);
        }

        if (result == null || result.size() < 3) {
            throw new IllegalStateException("대기열 등록 처리 중 Redis 스크립트 응답이 비어있습니다.");
        }

        long statusCode = result.get(0);
        long rank = result.get(1);

        if (LUA_DUPLICATED == statusCode) {
            throw new CouponDuplicatedException("이미 발급받았거나 처리 중인 쿠폰입니다.");
        }
        if (LUA_SOLD_OUT == statusCode) {
            throw new CouponSoldOutException("선착순 쿠폰이 모두 소진되었습니다.");
        }
        if (LUA_QUEUE_FULL == statusCode) {
            throw new QueueFullException(policyId);
        }
        if (LUA_NOT_INITIALIZED == statusCode) {
            throw new IllegalStateException(
                    "쿠폰 재고가 Redis에 초기화되지 않았습니다. 관리자에게 문의하세요. policyId=" + policyId);
        }

        // 4. 즉시 입장 (201 ADMITTED) vs 대기 (200 WAITING) 분기
        if (LUA_ADMITTED == statusCode) {
            return tryAdmit(policyId, userId);
        }

        long estimatedWait = Math.max(1L, rank * ESTIMATED_SECONDS_PER_PERSON);
        return QueueJoinResponse.waiting(rank, estimatedWait);
    }

    /**
     * 대기열 상태 조회 메인 로직 (폴링 API).
     *
     * <ol>
     *   <li>In-Memory 캐시 기반 정책 유효성 검증</li>
     *   <li>Redis Lua 원자적 상태 확인 (ADMITTED, WAITING, SOLD_OUT, ISSUED, NOT_FOUND)</li>
     *   <li>순번에 따른 동적 백오프(retryAfterSeconds) 계산 및 반환</li>
     * </ol>
     */
    public QueueStatusResponse getQueueStatus(Long policyId, Long userId) {
        // 1. 정책 유효성 검증 (In-Memory 캐시 조회)
        validatePolicy(policyId);

        // 2. Redis Lua 원자적 상태 조회
        List<String> keys = List.of(
                RedisKeys.activeUser(policyId, userId),
                RedisKeys.couponIssued(policyId),
                RedisKeys.couponStock(policyId),
                RedisKeys.couponQueue(policyId)
        );

        List<String> result;
        try {
            result = redisTemplate.execute(
                    getQueueStatusScript,
                    keys,
                    String.valueOf(userId)
            );
        } catch (Exception e) {
            log.error("Redis 대기열 상태 조회 실패. policyId={}, userId={}", policyId, userId, e);
            throw new QueueFullException(policyId);
        }

        if (result == null || result.size() < 3) {
            throw new IllegalStateException("대기열 상태 조회 중 Redis 스크립트 응답이 비어있습니다.");
        }

        String statusStr = result.get(0);
        String token = result.get(1);
        long rank = Long.parseLong(result.get(2));

        return switch (statusStr) {
            case "ADMITTED" -> QueueStatusResponse.admitted(token);
            case "WAITING" -> {
                long estimatedWait = Math.max(1L, rank * ESTIMATED_SECONDS_PER_PERSON);
                double retryAfter = calculateRetryAfter(rank);
                yield QueueStatusResponse.waiting(rank, estimatedWait, retryAfter);
            }
            case "SOLD_OUT" -> QueueStatusResponse.soldOut();
            case "ISSUED" -> throw new CouponDuplicatedException("이미 발급 완료된 쿠폰입니다.");
            default -> throw new CouponPolicyNotFoundException(policyId);
        };
    }

    /**
     * Polling Storm 완화를 위한 동적 백오프 계산.
     * 앞 대기자가 많을수록 재요청 주기를 길게 제안한다.
     */
    private double calculateRetryAfter(long rank) {
        if (rank > 100) {
            return 3.0;
        }
        if (rank > 50) {
            return 2.0;
        }
        if (rank > 10) {
            return 1.0;
        }
        return 0.5;
    }

    /**
     * 즉시 입장 케이스: activeToken 생성 및 Redis 저장 (소유자 userId 매핑 및 역방향 키 등록).
     */
    private QueueJoinResponse tryAdmit(Long policyId, Long userId) {
        String activeToken = UUID.randomUUID().toString().replace("-", "");
        String tokenKey = RedisKeys.activeToken(activeToken);
        String userKey = RedisKeys.activeUser(policyId, userId);
        try {
            redisTemplate.opsForValue().set(tokenKey, String.valueOf(userId), tokenTtlSeconds, TimeUnit.SECONDS);
            redisTemplate.opsForValue().set(userKey, activeToken, tokenTtlSeconds, TimeUnit.SECONDS);

            log.debug("대기열 즉시 입장: policyId={}, userId={}, token={}", policyId, userId, activeToken);
            return QueueJoinResponse.admitted(activeToken);
        } catch (Exception e) {
            log.warn("activeToken Redis 저장 실패. WAITING fallback. policyId={}, userId={}", policyId, userId, e);
            return QueueJoinResponse.waiting(0L, 0L);
        }
    }

    /**
     * In-Memory 캐시 기반 정책 유효성 검증.
     */
    private void validatePolicy(Long policyId) {
        CouponPolicyCacheService.CachedPolicy policy = couponPolicyCacheService.getPolicy(policyId);

        LocalDateTime now = LocalDateTime.now();

        if (policy.closeAt() != null && now.isAfter(policy.closeAt())) {
            throw new CouponNotOpenedException(policyId);
        }

        if (now.isBefore(policy.openAt())) {
            throw new CouponNotOpenedException(policyId, policy.openAt());
        }
    }
}
