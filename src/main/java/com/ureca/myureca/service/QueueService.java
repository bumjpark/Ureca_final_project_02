package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.queue.QueueStatus;
import com.ureca.myureca.dto.request.QueueJoinRequest;
import com.ureca.myureca.dto.response.QueueJoinResponse;
import com.ureca.myureca.exception.CouponDuplicatedException;
import com.ureca.myureca.exception.CouponNotOpenedException;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.CouponSoldOutException;
import com.ureca.myureca.exception.QueueFullException;
import com.ureca.myureca.repository.CouponPolicyRepository;
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
     * 300ms = 0초로 표시되므로 최소 1초 단위로 ceil 처리.
     */
    private static final long ESTIMATED_SECONDS_PER_PERSON = 1L;

    // Lua 스크립트 반환 상태 코드
    private static final long LUA_OK = 200L;
    private static final long LUA_DUPLICATED = 409L;
    private static final long LUA_SOLD_OUT = 400L;
    private static final long LUA_QUEUE_FULL = 503L;
    /** stock 키 자체가 Redis에 없는 경우 — 이벤트 미초기화 */
    private static final long LUA_NOT_INITIALIZED = 500L;

    private final CouponPolicyRepository couponPolicyRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> joinQueueScript;

    /**
     * 대기열 등록 메인 로직.
     *
     * <ol>
     *   <li>DB 정책 유효성 검증 (삭제 여부, 오픈 시각, 종료 시각)</li>
     *   <li>Redis Lua 원자적 실행 (중복·재고·정원 체크 + ZSET 등록)</li>
     *   <li>rank == 0 이면 activeToken 발급 후 ADMITTED 응답</li>
     *   <li>rank > 0 이면 WAITING 응답</li>
     * </ol>
     */
    public QueueJoinResponse joinQueue(QueueJoinRequest request) {
        Long policyId = request.policyId();
        Long userId = request.userId();

        // 1. 정책 유효성 검증 (DB 조회)
        validatePolicy(policyId);

        // 2. Redis Lua 원자적 실행
        List<String> keys = List.of(
                RedisKeys.couponIssued(policyId),
                RedisKeys.couponReserved(policyId),
                RedisKeys.couponStock(policyId),
                RedisKeys.couponQueue(policyId),
                RedisKeys.couponQueueSeq(policyId)
        );

        List<Long> result = redisTemplate.execute(
                joinQueueScript,
                keys,
                String.valueOf(userId),
                String.valueOf(maxQueueSize)
        );

        if (result == null || result.size() < 3) {
            throw new IllegalStateException("대기열 등록 처리 중 Redis 스크립트 응답이 비어있습니다.");
        }

        long statusCode = result.get(0);
        long rank = result.get(1);
        long queueLen = result.get(2);

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

        // 3. 즉시 입장 or 대기 분기
        // rank == 0 AND queueLen == 1: 대기열에 나 혼자 → 즉시 ADMITTED
        // rank == 0이더라도 queueLen > 1이면 앞 사람이 남아있는 것 → WAITING
        if (rank == 0 && queueLen == 1) {
            return tryAdmit(policyId, userId);
        }
        long estimatedWait = rank * ESTIMATED_SECONDS_PER_PERSON;
        return QueueJoinResponse.waiting(rank, estimatedWait);
    }

    /**
     * rank == 0 케이스: activeToken 생성 및 Redis 저장.
     *
     * <p>activeToken Redis 저장 실패 시 WAITING(rank=0)으로 fallback하여
     * 클라이언트가 {@code GET /queue/status}를 통해 재확인하도록 유도한다.
     * (부분 실패 방어 — Lua 성공 후 토큰 저장 장애 시 오버셀 없이 안전하게 처리)
     */
    private QueueJoinResponse tryAdmit(Long policyId, Long userId) {
        String activeToken = UUID.randomUUID().toString().replace("-", "");
        String tokenKey = RedisKeys.activeToken(activeToken);
        try {
            // value = userId 문자열 — consume 시 소유자 검증에 사용
            redisTemplate.opsForValue().set(tokenKey, String.valueOf(userId), tokenTtlSeconds, TimeUnit.SECONDS);
            log.debug("대기열 즉시 입장: policyId={}, userId={}, token={}", policyId, userId, activeToken);
            return QueueJoinResponse.admitted(activeToken);
        } catch (Exception e) {
            log.warn("activeToken Redis 저장 실패. WAITING fallback. policyId={}, userId={}", policyId, userId, e);
            return QueueJoinResponse.waiting(0L, 0L);
        }
    }

    /**
     * DB 기반 정책 유효성 검증.
     *
     * <ul>
     *   <li>삭제된 정책 → {@link CouponPolicyNotFoundException}</li>
     *   <li>이미 종료된 정책 → {@link CouponNotOpenedException}</li>
     *   <li>아직 오픈 전 정책 → {@link CouponNotOpenedException} (openAt 포함)</li>
     * </ul>
     */
    private void validatePolicy(Long policyId) {
        CouponPolicy policy = couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        LocalDateTime now = LocalDateTime.now();

        if (policy.getCloseAt() != null && now.isAfter(policy.getCloseAt())) {
            throw new CouponNotOpenedException(policyId);
        }

        if (now.isBefore(policy.getOpenAt())) {
            throw new CouponNotOpenedException(policyId, policy.getOpenAt());
        }
    }
}
