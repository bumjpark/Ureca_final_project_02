package com.ureca.myureca.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.support.RedisKeys;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QueueAdmissionSchedulerTest {

    @Mock private CouponPolicyRepository couponPolicyRepository;
    @Mock private QueueAdmissionService queueAdmissionService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private QueueAdmissionScheduler scheduler;

    private final Long POLICY_ID = 1L;
    private CouponPolicy activePolicy;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "admissionRate", 300);

        activePolicy = new CouponPolicy(
                "테스트 쿠폰", CouponType.FIXED, 1000, 10000,
                LocalDateTime.now().minusHours(1), // 이미 오픈됨
                LocalDateTime.now().plusDays(1)
        );
        ReflectionTestUtils.setField(activePolicy, "id", POLICY_ID);
    }

    @Test
    void 오픈_중인_정책에_대해_분산_락_획득_성공_시_admitUsers를_호출한다() {
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(RedisKeys.lockAdmission(POLICY_ID)), eq("locked"), eq(1L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        scheduler.processQueueAdmission();

        verify(queueAdmissionService).admitUsers(POLICY_ID, 300);
    }

    @Test
    void 다른_서버가_이미_락을_선점했으면_admitUsers를_호출하지_않는다() {
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(RedisKeys.lockAdmission(POLICY_ID)), eq("locked"), eq(1L), eq(TimeUnit.SECONDS)))
                .thenReturn(false); // 락 획득 실패

        scheduler.processQueueAdmission();

        verify(queueAdmissionService, never()).admitUsers(anyLong(), anyInt());
    }

    @Test
    void 오픈_전인_정책은_스케줄러_대상에서_제외된다() {
        CouponPolicy futurePolicy = new CouponPolicy(
                "오픈 전 쿠폰", CouponType.FIXED, 1000, 10000,
                LocalDateTime.now().plusHours(2), null
        );
        ReflectionTestUtils.setField(futurePolicy, "id", 2L);

        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(futurePolicy)));

        scheduler.processQueueAdmission();

        verify(queueAdmissionService, never()).admitUsers(anyLong(), anyInt());
    }

    @Test
    void 재고가_0인_정책은_admitUsers를_호출하지_않고_스킵한다() {
        when(couponPolicyRepository.findByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activePolicy)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(RedisKeys.lockAdmission(POLICY_ID)), eq("locked"), eq(1L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(valueOperations.get(RedisKeys.couponStock(POLICY_ID))).thenReturn("0");

        scheduler.processQueueAdmission();

        verify(queueAdmissionService, never()).admitUsers(anyLong(), anyInt());
    }
}
