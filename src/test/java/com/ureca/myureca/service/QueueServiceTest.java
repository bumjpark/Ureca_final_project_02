package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.queue.QueueStatus;
import com.ureca.myureca.dto.request.QueueJoinRequest;
import com.ureca.myureca.dto.response.QueueJoinResponse;
import com.ureca.myureca.dto.response.QueueStatusResponse;
import com.ureca.myureca.exception.CouponDuplicatedException;
import com.ureca.myureca.exception.CouponNotOpenedException;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.CouponSoldOutException;
import com.ureca.myureca.exception.QueueFullException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock private CouponPolicyCacheService couponPolicyCacheService;
    @Mock private QueueRateLimiter queueRateLimiter;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisScript<List<Long>> joinQueueScript;
    @Mock private RedisScript<List<String>> getQueueStatusScript;
    @Mock private ValueOperations<String, String> valueOperations;

    private QueueService queueService;

    private final Long POLICY_ID = 1L;
    private final Long USER_ID = 42L;
    private CouponPolicyCacheService.CachedPolicy openPolicy;

    @BeforeEach
    void setUp() {
        queueService = new QueueService(
                couponPolicyCacheService,
                queueRateLimiter,
                redisTemplate,
                joinQueueScript,
                getQueueStatusScript
        );
        ReflectionTestUtils.setField(queueService, "maxQueueSize", 30000L);
        ReflectionTestUtils.setField(queueService, "tokenTtlSeconds", 60L);

        openPolicy = new CouponPolicyCacheService.CachedPolicy(
                POLICY_ID,
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1),
                System.currentTimeMillis()
        );
    }

    // ─── joinQueue (대기열 등록) ───────────────────────────────────────────────

    @Test
    void 대기_인원이_있어_200을_받으면_WAITING과_순번을_반환한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(200L, 5L, 10L));

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isEqualTo(5L);
        assertThat(response.activeToken()).isNull();
        assertThat(response.estimatedWaitSeconds()).isEqualTo(5L);
    }

    @Test
    void 대기열이_비어있어_201을_받으면_즉시_ADMITTED와_activeToken을_반환한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(201L, 0L, 0L));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.ADMITTED);
        assertThat(response.rank()).isEqualTo(0L);
        assertThat(response.activeToken()).isNotNull().isNotBlank();
    }

    @Test
    void 이미_대기_중인_유저_재요청_시_기존_순번을_그대로_반환한다_멱등성() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(200L, 3L, 7L));

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isEqualTo(3L);
    }

    @Test
    void issued_셋에_있는_유저는_CouponDuplicatedException이_발생한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(409L, -1L, -1L));

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponDuplicatedException.class);
    }

    @Test
    void reserved_ZSET에_있는_유저도_CouponDuplicatedException이_발생한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(409L, -1L, -1L));

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponDuplicatedException.class);
    }

    @Test
    void 재고가_없으면_CouponSoldOutException이_발생한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(400L, -1L, -1L));

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponSoldOutException.class);
    }

    @Test
    void stock_키_미초기화_시_IllegalStateException이_발생한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(500L, -1L, -1L));

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("초기화");
    }

    @Test
    void 대기열_정원_초과시_QueueFullException이_발생한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(503L, -1L, -1L));

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(QueueFullException.class);
    }

    @Test
    void Redis_호출_장애_시_서킷브레이커로_QueueFullException_503을_던진다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis Connection Refused"));

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(QueueFullException.class);
    }

    // ─── getQueueStatus (대기열 상태 조회) ─────────────────────────────────────

    @Test
    void 상태_조회시_활성토큰이_있으면_ADMITTED와_토큰을_반환한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(getQueueStatusScript), anyList(), anyString()))
                .thenReturn(List.of("ADMITTED", "mytoken123", "0"));

        QueueStatusResponse response = queueService.getQueueStatus(POLICY_ID, USER_ID);

        assertThat(response.status()).isEqualTo(QueueStatus.ADMITTED);
        assertThat(response.activeToken()).isEqualTo("mytoken123");
        assertThat(response.rank()).isEqualTo(0L);
        assertThat(response.retryAfterSeconds()).isEqualTo(0.0);
    }

    @Test
    void 상태_조회시_대기_중이면_WAITING과_동적_백오프를_반환한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        // rank = 150 (대기자 100명 초과) -> retryAfter = 3.0초
        when(redisTemplate.execute(eq(getQueueStatusScript), anyList(), anyString()))
                .thenReturn(List.of("WAITING", "", "150"));

        QueueStatusResponse response = queueService.getQueueStatus(POLICY_ID, USER_ID);

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isEqualTo(150L);
        assertThat(response.estimatedWaitSeconds()).isEqualTo(150L);
        assertThat(response.retryAfterSeconds()).isEqualTo(3.0);
    }

    @Test
    void 대기_순번이_10명_이하이면_0점5초_빠른_폴링_백오프를_제안한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        // rank = 5 -> retryAfter = 0.5초
        when(redisTemplate.execute(eq(getQueueStatusScript), anyList(), anyString()))
                .thenReturn(List.of("WAITING", "", "5"));

        QueueStatusResponse response = queueService.getQueueStatus(POLICY_ID, USER_ID);

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isEqualTo(5L);
        assertThat(response.retryAfterSeconds()).isEqualTo(0.5);
    }

    @Test
    void 상태_조회시_재고_소진이면_SOLD_OUT을_반환한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(getQueueStatusScript), anyList(), anyString()))
                .thenReturn(List.of("SOLD_OUT", "", "-1"));

        QueueStatusResponse response = queueService.getQueueStatus(POLICY_ID, USER_ID);

        assertThat(response.status()).isEqualTo(QueueStatus.SOLD_OUT);
        assertThat(response.rank()).isEqualTo(-1L);
    }

    @Test
    void 상태_조회시_이미_발급_완료된_유저는_CouponDuplicatedException이_발생한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(getQueueStatusScript), anyList(), anyString()))
                .thenReturn(List.of("ISSUED", "", "-1"));

        assertThatThrownBy(() -> queueService.getQueueStatus(POLICY_ID, USER_ID))
                .isInstanceOf(CouponDuplicatedException.class);
    }

    @Test
    void 상태_조회시_대기열에_없는_유저는_CouponPolicyNotFoundException이_발생한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(getQueueStatusScript), anyList(), anyString()))
                .thenReturn(List.of("NOT_FOUND", "", "-1"));

        assertThatThrownBy(() -> queueService.getQueueStatus(POLICY_ID, USER_ID))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }
}
