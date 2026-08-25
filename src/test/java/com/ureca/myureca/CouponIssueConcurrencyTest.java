package com.ureca.myureca;

import com.ureca.myureca.dto.response.CouponIssueResponse;
import com.ureca.myureca.exception.CouponDuplicatedException;
import com.ureca.myureca.exception.CouponSoldOutException;
import com.ureca.myureca.exception.InvalidTokenException;
import com.ureca.myureca.service.CouponIssueService;
import com.ureca.myureca.support.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponIssueConcurrencyTest {

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final Long testPolicyId = 1L;

    @BeforeEach
    void setUp() {
        // Redis 테스트 키 초기화
        redisTemplate.delete(RedisKeys.couponStock(testPolicyId));
        redisTemplate.delete(RedisKeys.couponReserved(testPolicyId));
        redisTemplate.delete(RedisKeys.couponIssued(testPolicyId));
    }

    @Test
    @DisplayName("유효하지 않거나 등록되지 않은 토큰 요청 시 InvalidTokenException(403)이 발생해야 한다")
    void testInvalidTokenBlocked() {
        // Given
        Long userId = 1004L;
        String fakeToken = "fake-token-" + UUID.randomUUID();

        // When & Then
        assertThatThrownBy(() -> couponIssueService.issueCoupon(testPolicyId, userId, fakeToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("1인 1매 동시성 검증: 동일 유저 100회 동시 요청 시 정확히 1건만 ACCEPTED 되고 99건은 DUPLICATED(409) 차단되어야 한다")
    void testDuplicateIssuePrevention() throws InterruptedException {
        // Given: 총 재고 100개 세팅
        redisTemplate.opsForValue().set(RedisKeys.couponStock(testPolicyId), "100");

        int threadCount = 100;
        Long userId = 1004L;

        // 100개의 유효 토큰을 사전 발급 (토큰 통과 후 Redis Lua 동시성 제어 검증)
        String[] tokens = new String[threadCount];
        for (int i = 0; i < threadCount; i++) {
            tokens[i] = "token-user-" + i + "-" + UUID.randomUUID();
            redisTemplate.opsForValue().set(RedisKeys.activeToken(tokens[i]), "true");
        }

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // When: 동일 유저로 100개 스레드가 동시 요청
        for (int i = 0; i < threadCount; i++) {
            final String token = tokens[i];
            executorService.submit(() -> {
                try {
                    CouponIssueResponse response = couponIssueService.issueCoupon(testPolicyId, userId, token);
                    if ("ACCEPTED".equals(response.status())) {
                        successCount.incrementAndGet();
                    }
                } catch (CouponDuplicatedException e) {
                    duplicateCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Then:
        // 1. 단 1건만 발급 성공
        assertThat(successCount.get()).isEqualTo(1);
        // 2. 99건은 409 Duplicated 예외로 차단
        assertThat(duplicateCount.get()).isEqualTo(threadCount - 1);
        // 3. 기타 예외 0건
        assertThat(errorCount.get()).isEqualTo(0);

        // 4. Redis 상태 검증: 재고는 정확히 1만 차감되어 99개, RESERVED ZSET에는 1명만 등록
        String remainingStock = redisTemplate.opsForValue().get(RedisKeys.couponStock(testPolicyId));
        Long reservedSize = redisTemplate.opsForZSet().size(RedisKeys.couponReserved(testPolicyId));

        assertThat(remainingStock).isEqualTo("99");
        assertThat(reservedSize).isEqualTo(1L);
    }

    @Test
    @DisplayName("선착순 재고 한정 동시성 검증: 재고 100개에 1,000명 동시 요청 시 정확히 100건만 성공하고 900건은 품절(400) Fast-Fail 차단되어야 한다 (Zero Over-issue)")
    void testSoldOutFastFailConcurrency() throws InterruptedException {
        // Given: 재고 100개 세팅
        int totalStock = 100;
        int totalRequests = 1000;
        redisTemplate.opsForValue().set(RedisKeys.couponStock(testPolicyId), String.valueOf(totalStock));

        // 1,000명의 각기 다른 유저 토큰 사전 발급
        String[] tokens = new String[totalRequests];
        for (int i = 0; i < totalRequests; i++) {
            tokens[i] = "token-user-" + i + "-" + UUID.randomUUID();
            // value = userId 문자열 (ActiveTokenService.consume() 에서 소유자 검증에 사용)
            redisTemplate.opsForValue().set(RedisKeys.activeToken(tokens[i]), String.valueOf((long)(i + 1)));
        }

        ExecutorService executorService = Executors.newFixedThreadPool(64);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger soldOutCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // When: 1,000명의 서로 다른 유저가 동시 요청
        for (int i = 0; i < totalRequests; i++) {
            final long userId = i + 1;
            final String token = tokens[i];
            executorService.submit(() -> {
                try {
                    CouponIssueResponse response = couponIssueService.issueCoupon(testPolicyId, userId, token);
                    if ("ACCEPTED".equals(response.status())) {
                        successCount.incrementAndGet();
                    }
                } catch (CouponSoldOutException e) {
                    soldOutCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Then:
        // 1. 정확히 재고 수량(100건)만큼만 ACCEPTED 발급 (초과 발급 0건)
        assertThat(successCount.get()).isEqualTo(totalStock);
        // 2. 나머지 900건은 0.5ms 만에 Fast-Fail 품절(400) 차단
        assertThat(soldOutCount.get()).isEqualTo(totalRequests - totalStock);
        // 3. 기타 예외 0건
        assertThat(errorCount.get()).isEqualTo(0);

        // 4. Redis 잔여 재고 0 확인 및 RESERVED ZSET 크기 100 확인
        String remainingStock = redisTemplate.opsForValue().get(RedisKeys.couponStock(testPolicyId));
        Long reservedSize = redisTemplate.opsForZSet().size(RedisKeys.couponReserved(testPolicyId));

        assertThat(remainingStock).isEqualTo("0");
        assertThat(reservedSize).isEqualTo((long) totalStock);
    }
}
