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

        // 3. Redis Pipelining으로 활성 토큰 및 유저 매핑 초고속 일괄 등록
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

                    String tokenKey = RedisKeys.activeToken(activeToken);
                    String userKey = RedisKeys.activeUser(policyId, userId);

                    operations.opsForValue().set(tokenKey, String.valueOf(userId), tokenTtlSeconds, TimeUnit.SECONDS);
                    operations.opsForValue().set(userKey, activeToken, tokenTtlSeconds, TimeUnit.SECONDS);
                }
                return null;
            }
        });

        int admittedCount = poppedUsers.size();
        log.info("대기열 입장 처리 완료: policyId={}, admittedCount={}", policyId, admittedCount);
        return admittedCount;
    }
}
