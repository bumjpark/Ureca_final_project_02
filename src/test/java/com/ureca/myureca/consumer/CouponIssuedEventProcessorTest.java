package com.ureca.myureca.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponHistory;
import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.HistoryPrevStatus;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
class CouponIssuedEventProcessorTest {

    @Mock
    private CouponHistoryRepository couponHistoryRepository;
    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponPolicyRepository couponPolicyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private SetOperations<String, String> setOperations;

    private CouponIssuedEventProcessor processor;

    private static final String RECEIPT_ID = "rcpt_abc123def456";
    private static final Long POLICY_ID = 1L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        processor = new CouponIssuedEventProcessor(
                couponHistoryRepository, couponIssueRepository,
                couponPolicyRepository, userRepository, redisTemplate);
    }

    private CouponIssuedEvent sampleEvent() {
        return new CouponIssuedEvent(POLICY_ID, USER_ID, RECEIPT_ID, LocalDateTime.now());
    }

    // ─────────────────────────────────────────────────
    // 정상 케이스
    // ─────────────────────────────────────────────────

    @Test
    void 정상_처리시_couponIssue와_history가_저장되고_prevStatus는_NONE_newStatus는_ISSUED이다() {
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        // User 생성자: (email, name) 순
        User user = new User("test@test.com", "테스트유저");
        when(couponHistoryRepository.existsByRequestId(RECEIPT_ID)).thenReturn(false);
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(couponIssueRepository.save(any(CouponIssue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        processor.processSingle(sampleEvent());

        // CouponIssue 저장 검증
        verify(couponIssueRepository).save(any(CouponIssue.class));

        // CouponHistory 저장 검증 — prevStatus=NONE, newStatus=ISSUED
        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).save(historyCaptor.capture());

        CouponHistory savedHistory = historyCaptor.getValue();
        assertThat(savedHistory.getPrevStatus()).isEqualTo(HistoryPrevStatus.NONE);
        assertThat(savedHistory.getNewStatus()).isEqualTo(IssueStatus.ISSUED);
        assertThat(savedHistory.getRequestId()).isEqualTo(RECEIPT_ID);
        assertThat(savedHistory.getCancelReason()).isNull();

        // DB 저장 성공 후 Redis reserved→issued 갱신 검증
        verify(zSetOperations).remove("coupon:policy:" + POLICY_ID + ":reserved", String.valueOf(USER_ID));
        verify(setOperations).add("coupon:policy:" + POLICY_ID + ":issued", String.valueOf(USER_ID));
    }

    @Test
    void Redis_reserved_issued_갱신이_실패해도_예외가_전파되지_않는다() {
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");
        when(couponHistoryRepository.existsByRequestId(RECEIPT_ID)).thenReturn(false);
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(couponIssueRepository.save(any(CouponIssue.class))).thenAnswer(inv -> inv.getArgument(0));
        // DB 저장은 이미 끝났으므로, Redis 쪽 오류로 Kafka 재시도를 유발해서는 안 된다
        when(redisTemplate.opsForZSet()).thenThrow(new RuntimeException("Redis 커넥션 오류"));

        assertThatCode(() -> processor.processSingle(sampleEvent()))
                .doesNotThrowAnyException();

        // DB 저장 자체는 정상적으로 이뤄졌어야 한다
        verify(couponIssueRepository).save(any(CouponIssue.class));
        verify(couponHistoryRepository).save(any(CouponHistory.class));
    }

    // ─────────────────────────────────────────────────
    // 인박스 스킵 케이스
    // ─────────────────────────────────────────────────

    @Test
    void 이미_처리된_receiptId면_save를_호출하지_않고_정상_종료한다() {
        when(couponHistoryRepository.existsByRequestId(RECEIPT_ID)).thenReturn(true);

        assertThatCode(() -> processor.processSingle(sampleEvent()))
                .doesNotThrowAnyException();

        verify(couponIssueRepository, never()).save(any());
        verify(couponHistoryRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────
    // DataIntegrityViolationException — 2차 방어
    // ─────────────────────────────────────────────────

    @Test
    void DataIntegrityViolationException_발생시_예외가_전파되지_않고_정상_종료한다() {
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");
        when(couponHistoryRepository.existsByRequestId(RECEIPT_ID)).thenReturn(false);
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(couponIssueRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_policy_user 위반"));

        // DataIntegrityViolationException은 catch되어 밖으로 전파되지 않아야 한다
        assertThatCode(() -> processor.processSingle(sampleEvent()))
                .doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────
    // 기타 예외 — 재시도 유도
    // ─────────────────────────────────────────────────

    @Test
    void DB_커넥션_오류_등_기타_예외는_그대로_전파되어_DefaultErrorHandler가_재시도한다() {
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");
        when(couponHistoryRepository.existsByRequestId(RECEIPT_ID)).thenReturn(false);
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(couponIssueRepository.save(any())).thenThrow(new RuntimeException("DB 커넥션 오류"));

        // 그 외 예외는 catch하지 않고 위로 throw되어야 한다 (DefaultErrorHandler가 재시도)
        assertThatThrownBy(() -> processor.processSingle(sampleEvent()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB 커넥션 오류");
    }
}
