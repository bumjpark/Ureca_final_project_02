package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.queue.QueueStatus;
import com.ureca.myureca.dto.request.QueueJoinRequest;
import com.ureca.myureca.dto.response.QueueJoinResponse;
import com.ureca.myureca.exception.CouponDuplicatedException;
import com.ureca.myureca.exception.CouponNotOpenedException;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.CouponSoldOutException;
import com.ureca.myureca.exception.QueueFullException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    @Mock private CouponPolicyRepository couponPolicyRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisScript<List<Long>> joinQueueScript;
    @Mock private ValueOperations<String, String> valueOperations;

    private QueueService queueService;

    private final Long POLICY_ID = 1L;
    private final Long USER_ID = 42L;
    private CouponPolicy openPolicy;

    @BeforeEach
    void setUp() {
        queueService = new QueueService(couponPolicyRepository, redisTemplate, joinQueueScript);
        ReflectionTestUtils.setField(queueService, "maxQueueSize", 30000L);
        ReflectionTestUtils.setField(queueService, "tokenTtlSeconds", 60L);

        openPolicy = new CouponPolicy(
                "테스트 쿠폰", CouponType.FIXED, 1000, 10000,
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1)
        );
    }

    // ─── 정상 흐름 ─────────────────────────────────────────────────────────────

    @Test
    void 대기_중_정상_등록_시_WAITING과_순번을_반환한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        // queueLen=10: 나 혼자가 아님 → rank 5이므로 WAITING
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(200L, 5L, 10L));

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isEqualTo(5L);
        assertThat(response.activeToken()).isNull();
        assertThat(response.estimatedWaitSeconds()).isEqualTo(5L);
    }

    @Test
    void 대기열에_혼자일_때_rank0_이면_ADMITTED를_반환한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        // rank=0 AND queueLen=1: 나 혼자 → 즉시 ADMITTED
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(200L, 0L, 1L));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.ADMITTED);
        assertThat(response.rank()).isEqualTo(0L);
        assertThat(response.activeToken()).isNotNull().isNotBlank();
    }

    @Test
    void rank가_0이더라도_queueLen이_1보다_크면_WAITING을_반환한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        // rank=0이지만 queueLen=5: 앞에 사람이 있음 → WAITING (수정된 판단 조건 검증)
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(200L, 0L, 5L));

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.activeToken()).isNull();
    }

    @Test
    void 이미_대기_중인_유저_재요청_시_기존_순번을_그대로_반환한다_멱등성() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(200L, 3L, 7L));

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isEqualTo(3L);
    }

    // ─── Fast-Fail 방어 로직 ────────────────────────────────────────────────

    @Test
    void issued_셋에_있는_유저는_CouponDuplicatedException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(409L, -1L, -1L));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponDuplicatedException.class);
    }

    @Test
    void reserved_ZSET에_있는_유저도_CouponDuplicatedException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(409L, -1L, -1L));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponDuplicatedException.class);
    }

    @Test
    void 재고가_없으면_CouponSoldOutException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(400L, -1L, -1L));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponSoldOutException.class);
    }

    @Test
    void stock_키_미초기화_시_IllegalStateException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(500L, -1L, -1L));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("초기화");
    }

    @Test
    void 대기열_정원_초과시_QueueFullException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(503L, -1L, -1L));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(QueueFullException.class);
    }

    // ─── 정책 상태 검증 ─────────────────────────────────────────────────────

    @Test
    void 아직_오픈_전_정책은_CouponNotOpenedException이_발생하고_openAt을_포함한다() {
        LocalDateTime futureOpenAt = LocalDateTime.now().plusHours(2);
        CouponPolicy notOpenedYet = new CouponPolicy(
                "오픈 전 쿠폰", CouponType.FIXED, 1000, 10000, futureOpenAt, null
        );
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(notOpenedYet));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponNotOpenedException.class)
                .satisfies(ex -> {
                    CouponNotOpenedException e = (CouponNotOpenedException) ex;
                    assertThat(e.getOpenAt()).isEqualTo(futureOpenAt);
                });
    }

    @Test
    void 이미_종료된_정책은_CouponNotOpenedException이_발생한다() {
        CouponPolicy closedPolicy = new CouponPolicy(
                "종료된 쿠폰", CouponType.FIXED, 1000, 10000,
                LocalDateTime.now().minusDays(2),
                LocalDateTime.now().minusHours(1)
        );
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(closedPolicy));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID)))
                .isInstanceOf(CouponNotOpenedException.class)
                .hasMessageContaining("종료된");
    }

    @Test
    void 존재하지_않는_정책은_CouponPolicyNotFoundException이_발생한다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(999L))
                .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> queueService.joinQueue(new QueueJoinRequest(999L, USER_ID)))
                .isInstanceOf(CouponPolicyNotFoundException.class);
    }

    // ─── 부분 실패 방어 ─────────────────────────────────────────────────────

    @Test
    void activeToken_저장_실패시_WAITING_fallback으로_안전하게_처리된다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(POLICY_ID))
                .thenReturn(Optional.of(openPolicy));
        when(redisTemplate.execute(eq(joinQueueScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of(200L, 0L, 1L)); // rank=0, queueLen=1 → ADMITTED 시도
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RuntimeException("Redis 연결 실패"))
                .when(valueOperations).set(anyString(), anyString(), any(Long.class), any(TimeUnit.class));

        QueueJoinResponse response = queueService.joinQueue(new QueueJoinRequest(POLICY_ID, USER_ID));

        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.activeToken()).isNull();
    }
}
