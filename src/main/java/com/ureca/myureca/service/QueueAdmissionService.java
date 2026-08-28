package com.ureca.myureca.service;

import com.ureca.myureca.support.RedisKeys;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

/**
 * 대기열에 대기 중인 유저들을 순차적으로 통과시켜 입장권(activeToken)을 일괄 발급하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAdmissionService {

    private final StringRedisTemplate redisTemplate;
    private final QueueSseService queueSseService;

    /** 토큰 유효 시간(초). 기본 60초. */
    @Value("${coupon.queue.token-ttl-seconds:60}")
    private long tokenTtlSeconds;

    /**
     * 특정 쿠폰 정책의 대기열에서 최대 {@code batchSize}명의 유저를 원자적으로 꺼내어
     * 활성 토큰(activeToken)을 일괄 발급한다.
     *
     * @param policyId 대상 쿠폰 정책 ID
     * @param batchSize 한 번에 통과시킬 최대 인원수 (예: 300)
     * @return 실제 입장 처리된 유저 수
     */
    public int admitUsers(Long policyId, int batchSize) {
        String stockKey = RedisKeys.couponStock(policyId);
        String queueKey = RedisKeys.couponQueue(policyId);

        // 1. 재고 확인 (품절 시 조기 종료)
        String stockStr = redisTemplate.opsForValue().get(stockKey);
        if (stockStr == null) {
            log.debug("재고 키 미초기화로 대기열 입장 처리 스킵. policyId={}", policyId);
            return 0;
        }

        int currentStock = Integer.parseInt(stockStr);
        if (currentStock <= 0) {
            log.debug("재고 소진으로 대기열 입장 처리 스킵. policyId={}", policyId);
            return 0;
        }

        // 2. 잔여 재고와 배치 정원 중 작은 값만큼만 정밀 슬라이싱 (과다 입장 Over-Admission 방어)
        int actualBatchSize = Math.min(batchSize, currentStock);

        // 3. 대기열 ZSET에서 선두 N명 원자적 추출 (ZPOPMIN)
        Set<TypedTuple<String>> poppedUsers = redisTemplate.opsForZSet().popMin(queueKey, actualBatchSize);
        if (poppedUsers == null || poppedUsers.isEmpty()) {
            return 0;
        }

        java.util.Map<Long, String> admittedTokens = new java.util.HashMap<>();

        // 4. Redis Pipelining으로 활성 토큰 및 유저 매핑 초고속 일괄 등록
        try {
            redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (TypedTuple<String> tuple : poppedUsers) {
                        String userIdStr = tuple.getValue();
                        if (userIdStr == null) {
                            continue;
                        }
                        Long userId = Long.parseLong(userIdStr);
                        String activeToken = UUID.randomUUID().toString().replace("-", "");
                        admittedTokens.put(userId, activeToken);

                        String tokenKey = RedisKeys.activeToken(activeToken);
                        String userKey = RedisKeys.activeUser(policyId, userId);
                        String markerKey = RedisKeys.admittedMarker(policyId, userId);

                        operations.opsForValue().set(tokenKey, String.valueOf(userId), tokenTtlSeconds, TimeUnit.SECONDS);
                        operations.opsForValue().set(userKey, activeToken, tokenTtlSeconds, TimeUnit.SECONDS);
                        operations.opsForValue().set(markerKey, "1", tokenTtlSeconds + 300, TimeUnit.SECONDS);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            // 이슈 #22: popMin()은 이미 대기열 ZSET에서 유저를 제거한 뒤다 — 여기서 실패하면
            // 그 유저들은 토큰도 못 받고 대기열에서도 사라져 흔적 없이 순번을 잃는다(재고 차감
            // 전이라 정합성 사고는 아니지만, 유저 입장에서는 대기열 축출과 동일). 원래 스코어(seq)
            // 그대로 되돌려 다음 틱에 다시 뽑히게 하는 보상 트랜잭션으로 방어한다.
            compensateFailedAdmission(queueKey, poppedUsers);
            throw e;
        }

        // 5. SSE(Server-Sent Events) 실시간 푸시: 대기 중인 유저 브라우저에 activeToken 즉시 전달
        for (java.util.Map.Entry<Long, String> entry : admittedTokens.entrySet()) {
            queueSseService.sendAdmitted(policyId, entry.getKey(), entry.getValue());
        }

        int admittedCount = poppedUsers.size();
        log.info("대기열 입장 처리 완료: policyId={}, admittedCount={}", policyId, admittedCount);
        return admittedCount;
    }

    /**
     * 이슈 #22: 토큰 발급 파이프라인이 실패했을 때, 이미 {@code popMin}으로 대기열에서 빠진
     * 유저들을 원래 스코어(seq) 그대로 {@code ZADD}해 되돌린다. seq는 {@code join_queue.lua}가
     * {@code INCR}로 발급한 절대·불변 순번이라 되돌려도 다른 유저와 충돌하거나 순서가 뒤바뀌지
     * 않는다 — 다음 틱에서 다시 같은 유저들이 선두로 뽑힌다.
     *
     * <p>이 보상 자체가 실패할 가능성(Redis가 완전히 죽어있는 등)도 있으므로 별도로 감싸서,
     * 원래 예외(파이프라인 실패)를 삼키지 않고 보상 실패는 CRITICAL 로그로만 남긴다 — 이 경우
     * 유저들은 정말로 대기열에서 유실된다(추적할 유일한 단서가 이 로그다).
     */
    private void compensateFailedAdmission(String queueKey, Set<TypedTuple<String>> poppedUsers) {
        try {
            for (TypedTuple<String> tuple : poppedUsers) {
                String userIdStr = tuple.getValue();
                Double seq = tuple.getScore();
                if (userIdStr == null || seq == null) {
                    continue;
                }
                redisTemplate.opsForZSet().add(queueKey, userIdStr, seq);
            }
            log.warn("대기열 입장 토큰 발급 실패 - popMin된 {}명을 원래 순번으로 되돌렸습니다. queueKey={}",
                    poppedUsers.size(), queueKey);
        } catch (Exception compensationEx) {
            log.error("CRITICAL: 대기열 입장 토큰 발급 실패 + 보상(원복)마저 실패 - {}명이 대기열에서 "
                            + "완전히 유실됐을 수 있습니다. queueKey={}",
                    poppedUsers.size(), queueKey, compensationEx);
        }
    }
}
