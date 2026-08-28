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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    void DataIntegrityViolationException_발생시_로그만_남기고_그대로_다시_던진다() {
        // 이슈 #11: 여기서 삼키고 정상 종료하면 이미 rollback-only로 마킹된 트랜잭션을 커밋하려다
        // UnexpectedRollbackException이 추가로 터진다(실측 확인) — 그래서 다시 던져 예외 기반으로
        // "정상적으로" 롤백시켜야 한다. 이 예외를 "정상적인 중복 스킵"으로 처리하는 건
        // 호출부(KafkaCouponEventConsumer)의 책임이다.
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");
        when(couponHistoryRepository.existsByRequestId(RECEIPT_ID)).thenReturn(false);
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        DataIntegrityViolationException original = new DataIntegrityViolationException("uk_policy_user 위반");
        when(couponIssueRepository.save(any())).thenThrow(original);

        assertThatThrownBy(() -> processor.processSingle(sampleEvent()))
                .isSameAs(original);
    }

    // ─────────────────────────────────────────────────
    // 이슈 #17 — FK 위반과 UNIQUE 위반을 로그상 구분한다
    // ─────────────────────────────────────────────────

    @Test
    void FK_위반은_UNIQUE_위반과_다르게_로깅되지만_예외는_동일하게_다시_던져진다() {
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");
        when(couponHistoryRepository.existsByRequestId(RECEIPT_ID)).thenReturn(false);
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        java.sql.SQLIntegrityConstraintViolationException sqlCause = new java.sql.SQLIntegrityConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`coupon_db`.`coupon_issue`, CONSTRAINT `fk_issue_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`))");
        DataIntegrityViolationException original = new DataIntegrityViolationException("FK 위반", sqlCause);
        when(couponIssueRepository.save(any())).thenThrow(original);

        assertThatThrownBy(() -> processor.processSingle(sampleEvent()))
                .isSameAs(original);
    }

    // ─────────────────────────────────────────────────
    // processChunk — 청크 처리(정상 경로)
    // ─────────────────────────────────────────────────

    @Test
    void 청크_정상_처리시_배치_SELECT로_중복없음_확인하고_전부_저장한다() {
        CouponIssuedEvent event1 = new CouponIssuedEvent(POLICY_ID, 1L, "rcpt_1", LocalDateTime.now());
        CouponIssuedEvent event2 = new CouponIssuedEvent(POLICY_ID, 2L, "rcpt_2", LocalDateTime.now());
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");

        when(couponHistoryRepository.findExistingRequestIds(List.of("rcpt_1", "rcpt_2")))
                .thenReturn(Set.of());
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(1L)).thenReturn(user);
        when(userRepository.getReferenceById(2L)).thenReturn(user);
        when(couponIssueRepository.save(any(CouponIssue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()))
                .thenReturn(List.of());

        processor.processChunk(List.of(event1, event2));

        verify(couponIssueRepository, org.mockito.Mockito.times(2)).save(any(CouponIssue.class));
        verify(couponHistoryRepository, org.mockito.Mockito.times(2)).save(any(CouponHistory.class));
        // 건별(processSingle)과 달리 Redis는 파이프라인 1번으로 묶인다
        verify(redisTemplate).executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any());
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void 청크_안에_이미_처리된_receiptId가_있으면_그것만_스킵하고_나머지는_저장한다() {
        CouponIssuedEvent duplicated = new CouponIssuedEvent(POLICY_ID, 1L, "rcpt_1", LocalDateTime.now());
        CouponIssuedEvent fresh = new CouponIssuedEvent(POLICY_ID, 2L, "rcpt_2", LocalDateTime.now());
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");

        when(couponHistoryRepository.findExistingRequestIds(List.of("rcpt_1", "rcpt_2")))
                .thenReturn(Set.of("rcpt_1"));
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(2L)).thenReturn(user);
        when(couponIssueRepository.save(any(CouponIssue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()))
                .thenReturn(List.of());

        processor.processChunk(List.of(duplicated, fresh));

        // 중복(rcpt_1)은 저장 시도조차 안 하고, 신규(rcpt_2)만 저장된다
        verify(couponIssueRepository, org.mockito.Mockito.times(1)).save(any(CouponIssue.class));
        verify(userRepository, never()).getReferenceById(1L);
    }

    @Test
    void 청크_전체가_중복이면_저장도_Redis_갱신도_안_한다() {
        CouponIssuedEvent duplicated = new CouponIssuedEvent(POLICY_ID, 1L, "rcpt_1", LocalDateTime.now());
        when(couponHistoryRepository.findExistingRequestIds(List.of("rcpt_1")))
                .thenReturn(Set.of("rcpt_1"));

        processor.processChunk(List.of(duplicated));

        verify(couponIssueRepository, never()).save(any());
        verify(redisTemplate, never()).executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any());
    }

    @Test
    void 청크_처리_중_예외가_나면_삼키지_않고_그대로_전파한다() {
        CouponIssuedEvent event = new CouponIssuedEvent(POLICY_ID, USER_ID, RECEIPT_ID, LocalDateTime.now());
        when(couponHistoryRepository.findExistingRequestIds(List.of(RECEIPT_ID))).thenReturn(Set.of());
        when(couponPolicyRepository.getReferenceById(POLICY_ID))
                .thenThrow(new DataIntegrityViolationException("제약 위반"));

        // processSingle과 달리 processChunk는 DataIntegrityViolationException도 삼키지 않는다 —
        // 청크 안 다른 이벤트까지 함께 롤백되므로, 호출부(Consumer)가 건별 폴백하도록 그대로 던진다.
        assertThatThrownBy(() -> processor.processChunk(List.of(event)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─────────────────────────────────────────────────
    // 이슈 #10 — Redis 반영은 트랜잭션 커밋 이후에만 실행돼야 한다
    // ─────────────────────────────────────────────────

    @Test
    void 트랜잭션_동기화가_활성_상태면_Redis_갱신은_커밋_전에는_실행되지_않는다() {
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");
        when(couponHistoryRepository.existsByRequestId(RECEIPT_ID)).thenReturn(false);
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(couponIssueRepository.save(any(CouponIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            processor.processSingle(sampleEvent());

            // flush까지는 실행됐지만(=DB 저장 자체는 끝), 트랜잭션이 아직 커밋되지 않았으므로
            // Redis 반영은 콜백으로 등록만 되고 아직 실행되면 안 된다.
            verify(couponIssueRepository).save(any(CouponIssue.class));
            verify(redisTemplate, never()).opsForZSet();
            verify(redisTemplate, never()).opsForSet();

            // 커밋 시뮬레이션 — 이 시점에야 등록해둔 콜백이 실행된다.
            when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCommit());

            verify(zSetOperations).remove("coupon:policy:" + POLICY_ID + ":reserved", String.valueOf(USER_ID));
            verify(setOperations).add("coupon:policy:" + POLICY_ID + ":issued", String.valueOf(USER_ID));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 청크_처리도_트랜잭션_동기화가_활성_상태면_Redis_갱신이_커밋_이후로_미뤄진다() {
        CouponIssuedEvent event = new CouponIssuedEvent(POLICY_ID, USER_ID, RECEIPT_ID, LocalDateTime.now());
        CouponPolicy policy = new CouponPolicy("테스트 정책", CouponType.FIXED, 1000, 100,
                LocalDateTime.now().minusDays(1), null);
        User user = new User("test@test.com", "테스트유저");
        when(couponHistoryRepository.findExistingRequestIds(List.of(RECEIPT_ID))).thenReturn(Set.of());
        when(couponPolicyRepository.getReferenceById(POLICY_ID)).thenReturn(policy);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(couponIssueRepository.save(any(CouponIssue.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            processor.processChunk(List.of(event));

            verify(redisTemplate, never())
                    .executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any());

            when(redisTemplate.executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()))
                    .thenReturn(List.of());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCommit());

            verify(redisTemplate).executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
