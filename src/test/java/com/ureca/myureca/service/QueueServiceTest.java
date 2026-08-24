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
    @Mock private KafkaCouponEventProducer kafkaCouponEventProducer;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisScript<List<Long>> joinQueueScript;
    @Mock private ValueOperations<String, String> valueOperations;

    private QueueService queueService;

    private final Long POLICY_ID = 1L;
    private final Long USER_ID = 42L;
    private CouponPolicyCacheService.CachedPolicy openPolicy;

    @BeforeEach
    void setUp() {
        queueService = new QueueService(couponPolicyCacheService, queueRateLimiter, kafkaCouponEventProducer, redisTemplate, joinQueueScript);
        ReflectionTestUtils.setField(queueService, "maxQueueSize", 30000L);
        ReflectionTestUtils.setField(queueService, "tokenTtlSeconds", 60L);

        openPolicy = new CouponPolicyCacheService.CachedPolicy(
                POLICY_ID,
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1),
                System.currentTimeMillis()
        );
    }

    // ─── 정상 흐름 ─────────────────────────────────────────────────────────────

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
        org.mockito.Mockito.verify(kafkaCouponEventProducer).publishQueueJoinEvent(any(com.ureca.myureca.dto.event.QueueJoinEvent.class));
    }

    @Test
    void 대기열이_비어있어_201을_받으면_즉시_ADMITTED와_activeToken을_반환한다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(201L, 0L, 0L));

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

    // ─── Fast-Fail 방어 로직 ────────────────────────────────────────────────

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

    // ─── 정책 상태 검증 ─────────────────────────────────────────────────────

    @Test
    void 아직_오픈_전_정책은_CouponNotOpenedException이_발생하고_openAt을_포함한다() {
        LocalDateTime futureOpenAt = LocalDateTime.now().plusHours(2);
        CouponPolicyCacheService.CachedPolicy notOpenedYet = new CouponPolicyCacheService.CachedPolicy(
                POLICY_ID, futureOpenAt, null, System.currentTimeMillis()
        );
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(notOpenedYet);

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponNotOpenedException.class)
                .satisfies(ex -> {
                    CouponNotOpenedException e = (CouponNotOpenedException) ex;
                    assertThat(e.getOpenAt()).isEqualTo(futureOpenAt);
                });
    }

    @Test
    void 이미_종료된_정책은_CouponNotOpenedException이_발생한다() {
        CouponPolicyCacheService.CachedPolicy closedPolicy = new CouponPolicyCacheService.CachedPolicy(
                POLICY_ID,
                LocalDateTime.now().minusDays(2),
                LocalDateTime.now().minusHours(1),
                System.currentTimeMillis()
        );
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(closedPolicy);

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponNotOpenedException.class)
                .hasMessageContaining("종료된");
    }

    @Test
    void 존재하지_않는_정책은_CouponPolicyNotFoundException이_발생한다() {
        when(couponPolicyCacheService.getPolicy(999L))
                .thenThrow(new CouponPolicyNotFoundException(999L));

        assertThatThrownBy(() -> queueService.joinQueue(new QueueJoinRequest(999L, USER_ID)))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }

    // ─── 부분 실패 방어 ─────────────────────────────────────────────────────

    @Test
    void activeToken_저장_실패시_WAITING_fallback으로_안전하게_처리된다() {
        when(couponPolicyCacheService.getPolicy(POLICY_ID)).thenReturn(openPolicy);
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(201L, 0L, 0L));
        when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.SessionCallback.class)))
                .thenThrow(new RuntimeException("Redis 연결 실패"));

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.activeToken()).isNull();
    }
}
