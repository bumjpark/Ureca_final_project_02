package com.ureca.myureca.service;

import com.ureca.myureca.support.RedisKeys;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
    private final RedisScript<List<String>> admitBatchScript;

    /** 토큰 유효 시간(초). 기본 60초. */
    @Value("${coupon.queue.token-ttl-seconds:60}")
    private long tokenTtlSeconds;

    /**
     * 이 시간을 넘도록 pending(입장 후 미확정) ZSET에 남아있으면 "이탈"로 보고 걷어낸다.
     * 재고를 되돌리는 게 아니라({@code pending}은 애초에 재고를 건드리지 않는다) 다음 입장
     * 배치의 계산에서 이 인원을 더 이상 "아직 발급 확정 안 됨"으로 세지 않게 하는 것뿐이다.
     * activeToken TTL({@link #tokenTtlSeconds})과 같은 값이 정상이지만, 스케줄러 틱 간격(1초)
     * 만큼 여유를 더 둬서 "토큰은 아직 유효한데 pending은 이미 걷힌" 경계 케이스를 없앤다.
     */
    @Value("${coupon.queue.pending-reclaim-grace-seconds:5}")
    private long pendingReclaimGraceSeconds;

    /**
     * 특정 쿠폰 정책의 대기열에서 최대 {@code batchSize}명의 유저를 원자적으로 꺼내어
     * 활성 토큰(activeToken)을 일괄 발급한다.
     *
     * <p>실제로 몇 명을 꺼낼지는 {@code batchSize}와 재고뿐 아니라, 아직 {@code /issue}를
     * 부르지 않은 이전 입장자 수(pending)도 함께 고려한다({@code admit_batch.lua} 참고) —
     * 그래야 여러 틱에 걸쳐 같은 재고를 놓고 입장자들이 겹쳐서 경쟁하지 않는다.
     *
     * @param policyId 대상 쿠폰 정책 ID
     * @param batchSize 한 번에 통과시킬 최대 인원수(상한 — 실제로는 이보다 적게 뽑힐 수 있음)
     * @return 실제 입장 처리된 유저 수
     */
    public int admitUsers(Long policyId, int batchSize) {
        String queueKey = RedisKeys.couponQueue(policyId);
        String pendingKey = RedisKeys.couponPending(policyId);

        List<String> admittedUserIds = redisTemplate.execute(
                admitBatchScript,
                List.of(RedisKeys.couponStock(policyId), queueKey, pendingKey),
                String.valueOf(batchSize),
                String.valueOf(System.currentTimeMillis()));

        if (admittedUserIds == null || admittedUserIds.isEmpty()) {
            return 0;
        }

        java.util.Map<Long, String> admittedTokens = new java.util.HashMap<>();

        // Redis Pipelining으로 활성 토큰 및 유저 매핑 초고속 일괄 등록
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (String userIdStr : admittedUserIds) {
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
            // 이슈 #22: admit_batch.lua는 이미 대기열 ZSET에서 유저를 제거하고 pending에 넣은
            // 뒤다 — 여기서 실패하면 그 유저들은 토큰도 못 받고 대기열에서도 사라져 흔적 없이
            // 순번을 잃는다(재고를 아직 안 건드렸으니 정합성 사고는 아니지만, 유저 입장에서는
            // 대기열 축출과 동일). 원래 스코어(seq) 그대로 되돌리고 pending에서도 빼는
            // 보상 트랜잭션으로 방어한다.
            compensateFailedAdmission(queueKey, pendingKey, admittedUserIds);
            throw e;
        }

        // SSE(Server-Sent Events) 실시간 푸시: 대기 중인 유저 브라우저에 activeToken 즉시 전달
        for (java.util.Map.Entry<Long, String> entry : admittedTokens.entrySet()) {
            queueSseService.sendAdmitted(policyId, entry.getKey(), entry.getValue());
        }

        int admittedCount = admittedUserIds.size();
        log.info("대기열 입장 처리 완료: policyId={}, admittedCount={}", policyId, admittedCount);
        return admittedCount;
    }

    /**
     * 이슈 #22: 토큰 발급 파이프라인이 실패했을 때, 이미 {@code admit_batch.lua}로 대기열에서
     * 빠지고 pending에 들어간 유저들을 원상복구한다 — 대기열엔 원래 순번(seq) 그대로 되돌리고,
     * pending에선 뺀다(토큰을 못 받았으니 "발급 확정 대기 중"이 아니라 그냥 실패한 시도다).
     * seq는 {@code join_queue.lua}가 {@code INCR}로 발급한 절대·불변 순번이라 되돌려도 다른
     * 유저와 충돌하거나 순서가 뒤바뀌지 않는다 — 다음 틱에서 다시 같은 유저들이 선두로 뽑힌다.
     *
     * <p>이 보상 자체가 실패할 가능성(Redis가 완전히 죽어있는 등)도 있으므로 별도로 감싸서,
     * 원래 예외(파이프라인 실패)를 삼키지 않고 보상 실패는 CRITICAL 로그로만 남긴다 — 이 경우
     * 유저들은 정말로 대기열에서 유실된다(추적할 유일한 단서가 이 로그다).
     */
    private void compensateFailedAdmission(String queueKey, String pendingKey, List<String> admittedUserIds) {
        try {
            for (String userIdStr : admittedUserIds) {
                if (userIdStr == null) {
                    continue;
                }
                Double originalSeq = redisTemplate.opsForZSet().score(pendingKey, userIdStr);
                redisTemplate.opsForZSet().remove(pendingKey, userIdStr);
                if (originalSeq != null) {
                    redisTemplate.opsForZSet().add(queueKey, userIdStr, originalSeq);
                }
            }
            log.warn("대기열 입장 토큰 발급 실패 - admit_batch.lua로 뽑힌 {}명을 원래 순번으로 되돌렸습니다. queueKey={}",
                    admittedUserIds.size(), queueKey);
        } catch (Exception compensationEx) {
            log.error("CRITICAL: 대기열 입장 토큰 발급 실패 + 보상(원복)마저 실패 - {}명이 대기열에서 "
                            + "완전히 유실됐을 수 있습니다. queueKey={}",
                    admittedUserIds.size(), queueKey, compensationEx);
        }
    }

    /**
     * pending(입장 후 미확정) ZSET에서 임계 시간을 넘긴 항목을 걷어낸다. 재고를 되돌리지
     * 않는다 — {@code pending}은 애초에 재고를 건드리지 않았으므로, 여기서 지우는 것만으로
     * 다음 {@link #admitUsers}의 {@code available = stock - pending개수} 계산이 자동으로
     * 정확해진다. 걷어낸 유저는 재입장하려면 대기열에 새로 진입해야 한다(원래 순번은
     * 복구하지 않는다 — 시간 안에 발급을 끝내지 못한 것은 대기열 시스템의 실패가 아니라
     * 그 유저의 이탈이라 판단).
     *
     * @return 걷어낸(이탈로 판단한) 유저 수
     */
    public long reclaimStalePendingAdmissions(Long policyId) {
        long cutoffMillis = System.currentTimeMillis()
                - java.time.Duration.ofSeconds(tokenTtlSeconds + pendingReclaimGraceSeconds).toMillis();
        Long removed = redisTemplate.opsForZSet()
                .removeRangeByScore(RedisKeys.couponPending(policyId), Double.NEGATIVE_INFINITY, cutoffMillis);
        if (removed != null && removed > 0) {
            log.info("정책 id={} 입장 후 이탈로 판단해 pending에서 {}명 걷어냄(TTL {}초 + 여유 {}초 초과)",
                    policyId, removed, tokenTtlSeconds, pendingReclaimGraceSeconds);
        }
        return removed == null ? 0L : removed;
    }
}
