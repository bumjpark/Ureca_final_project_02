package com.ureca.myureca.consumer;

import com.ureca.myureca.domain.coupon.CouponHistory;
import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.HistoryPrevStatus;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.dto.event.CouponIssuedEvent;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.UserRepository;
import com.ureca.myureca.support.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Kafka Consumer가 수신한 CouponIssuedEvent를 DB에 반영하는 처리기.
 *
 * <p>DB 반영 성공 시 Redis의 발급 상태(reserved→issued)도 함께 정리한다
 * ({@link #confirmRedisState}, {@link #confirmRedisStateBatch}). 그렇게 하지 않으면
 * reserved가 영구히 쌓이고 issued가 계속 비어있어, 정합성 검증 배치가 정상 발급 건까지
 * 전부 불일치로 오판하게 된다.
 *
 * <p>이 Bean이 {@code @KafkaListener}와 별도로 분리된 이유:
 * Spring AOP 프록시는 self-invoke(같은 클래스 내부 메서드 호출)에서 동작하지 않기 때문에
 * {@code @KafkaListener}와 {@code @Transactional}이 같은 클래스에 있으면 트랜잭션이 적용되지 않는다.
 *
 * <p><b>두 가지 처리 경로 (성능 개선, SEQUENCE 전환과 짝을 이룸 — V7 마이그레이션 참고)</b>
 * <ul>
 *   <li>{@link #processChunk}: 정상 경로. 청크(기본 100건) 전체를 한 트랜잭션으로 묶어
 *       인박스 체크를 배치 SELECT 1번으로, INSERT들을 JDBC batch로 처리한다.
 *       청크 안에 중복/제약위반이 하나라도 있으면 트랜잭션 전체가 롤백된다(MySQL은
 *       제약 위반이 나면 트랜잭션을 즉시 무효화하는 경우가 많아, 그 안의 다른 정상
 *       이벤트까지 손실 위험이 있다) — 그래서 이 메서드는 예외를 삼키지 않고 그대로
 *       던져, 호출부(KafkaCouponEventConsumer)가 청크 실패를 감지해 폴백하게 한다.</li>
 *   <li>{@link #processSingle}: 폴백 경로. 청크 처리가 실패했을 때만 그 청크에 한해
 *       이벤트 단위로 다시 호출된다. 기존처럼 인박스 체크(1차) + DB UNIQUE 제약(2차)
 *       이중 방어로 안전하게 하나씩 처리하되, 여기서 실패하는 이벤트만 별도로 격리된다
 *       (같은 청크의 다른 정상 이벤트는 이 재처리에서 이미 성공적으로 반영됨).</li>
 * </ul>
 * 즉 "정상적인 대다수"는 빠른 청크 경로로, "문제 있는 소수"만 느리지만 안전한 단건
 * 경로로 자연스럽게 갈라진다 — 처리량과 실패 격리(poison-pill 범위 최소화)를 동시에 잡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssuedEventProcessor {

    private final CouponHistoryRepository couponHistoryRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponPolicyRepository couponPolicyRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * 청크(여러 이벤트)를 한 트랜잭션으로 처리한다 — 정상 경로.
     *
     * <p>1) 청크 전체 receiptId를 배치 SELECT 1번으로 조회해 이미 처리된 것만 걸러낸다
     * (이벤트마다 SELECT 한 번씩 하던 것을 청크당 1번으로 줄인 것).
     * 2) 나머지는 순서대로 save()만 호출한다 — SEQUENCE 전략(V7)이라 persist() 시점에
     * flush를 강제하지 않으므로, 실제 INSERT는 트랜잭션 커밋 시 한 번에 flush되며
     * hibernate.jdbc.batch_size + order_inserts 설정 덕분에 JDBC batch로 묶여 나간다.
     *
     * <p>청크 안에서 UNIQUE 제약 위반 등 예외가 나면(동시 진입으로 인한 극히 드문 중복 등)
     * 이 메서드는 잡지 않고 그대로 던진다 — 예외를 잡아버리면 이미 트랜잭션이 무효화된
     * 상태라 이후 로직도 실패하고, 무엇보다 이 청크의 다른 정상 이벤트까지 함께 롤백된
     * 채로 "처리됐다"고 착각하게 된다. 호출부가 이 예외를 받아 {@link #processSingle}로
     * 건별 폴백해야 청크 안의 정상 이벤트를 구제할 수 있다.
     *
     * @param events 같은 청크에 속한 이벤트들
     */
    @Transactional
    public void processChunk(List<CouponIssuedEvent> events) {
        List<String> receiptIds = events.stream().map(CouponIssuedEvent::receiptId).toList();
        Set<String> alreadyProcessed = couponHistoryRepository.findExistingRequestIds(receiptIds);

        List<CouponIssue> savedIssues = new ArrayList<>();
        for (CouponIssuedEvent event : events) {
            if (alreadyProcessed.contains(event.receiptId())) {
                log.info("[KafkaConsumer] 중복 이벤트 스킵(청크 배치 체크) — receiptId={}, policyId={}, userId={}",
                        event.receiptId(), event.policyId(), event.userId());
                continue;
            }

            CouponPolicy couponPolicy = couponPolicyRepository.getReferenceById(event.policyId());
            User user = userRepository.getReferenceById(event.userId());
            CouponIssue couponIssue = new CouponIssue(couponPolicy, user, event.receiptId(), event.issuedAt());
            couponIssueRepository.save(couponIssue);

            CouponHistory history = new CouponHistory(
                    couponIssue, event.receiptId(), HistoryPrevStatus.NONE, IssueStatus.ISSUED, null);
            couponHistoryRepository.save(history);

            savedIssues.add(couponIssue);
        }

        // SEQUENCE 전략은 save() 시점에 flush를 강제하지 않는다 — 즉 이 시점까지는 INSERT가
        // 실제로 DB에 나가지 않았고, FK/UNIQUE 위반 여부도 알 수 없다. flush()로 지금 당장
        // 물리적으로 내보내 예외를 여기서 터뜨려야, 그 아래 confirmRedisStateBatch가 "DB
        // 반영이 확정된 건"에 대해서만 실행된다는 이 클래스의 불변조건이 유지된다.
        // (order_inserts 설정 덕에 flush 자체는 여전히 JDBC batch로 묶여 나간다.)
        couponIssueRepository.flush();

        log.info("[KafkaConsumer] 청크 처리 완료 — 전체 {}건 중 신규 저장 {}건(중복 스킵 {}건)",
                events.size(), savedIssues.size(), events.size() - savedIssues.size());

        confirmRedisStateBatchAfterCommit(savedIssues);
    }

    /**
     * 이벤트 하나를 처리하여 coupon_issue, coupon_history에 INSERT한다.
     *
     * <p>예외 처리 갈래:
     * <ul>
     *   <li>{@link DataIntegrityViolationException} → 로그만 남기고 그대로 다시 던진다(이슈 #11).
     *       삼키고 정상 종료하면 이미 rollback-only로 마킹된 트랜잭션을 커밋하려다
     *       {@code UnexpectedRollbackException}이 추가로 터진다 — 이 예외를 정상적인 "중복
     *       스킵"으로 처리하는 건 호출부({@code KafkaCouponEventConsumer})의 책임이다.</li>
     *   <li>그 외 예외 → catch하지 않고 위로 throw. DefaultErrorHandler가 FixedBackOff로 재시도.</li>
     * </ul>
     *
     * @param event Kafka로 수신한 CouponIssuedEvent
     */
    @Transactional
    public void processSingle(CouponIssuedEvent event) {
        // 1차 방어: 인박스 패턴 — 이미 처리된 receiptId면 스킵
        // event.receiptId() == coupon_history.request_id (Producer 필드명 vs DB 컬럼명 차이)
        if (couponHistoryRepository.existsByRequestId(event.receiptId())) {
            log.info("[KafkaConsumer] 중복 이벤트 스킵 — receiptId={}, policyId={}, userId={}",
                    event.receiptId(), event.policyId(), event.userId());
            return;
        }

        try {
            // 2. coupon_issue INSERT (최초, status=ISSUED)
            CouponPolicy couponPolicy = couponPolicyRepository.getReferenceById(event.policyId());
            User user = userRepository.getReferenceById(event.userId());
            CouponIssue couponIssue = new CouponIssue(couponPolicy, user, event.receiptId(), event.issuedAt());
            couponIssueRepository.save(couponIssue);

            // 3. coupon_history INSERT
            // prevStatus = NONE: 최초 발급이므로 이전 상태 없음 (HistoryPrevStatus 전용 값)
            // newStatus  = ISSUED: coupon_issue.status와 동일하게 ISSUED로 기록
            // requestId  = event.receiptId(): Producer의 receiptId → history의 request_id에 매핑
            CouponHistory history = new CouponHistory(
                    couponIssue,
                    event.receiptId(),   // receiptId(event 필드명) → requestId(entity 필드명 = DB request_id)
                    HistoryPrevStatus.NONE,
                    IssueStatus.ISSUED,
                    null
            );
            couponHistoryRepository.save(history);

            // processChunk와 동일한 이유로 flush 강제: SEQUENCE 전략에서 save()는 flush를
            // 보장하지 않으므로, DataIntegrityViolationException을 catch절이 잡으려면
            // 여기서 지금 당장 flush해 실제 INSERT를 터뜨려야 한다. 그래야 confirmRedisState도
            // "DB 반영이 확정된 건"에 대해서만 실행된다.
            couponHistoryRepository.flush();

            log.info("[KafkaConsumer] 쿠폰 발급 DB 반영 완료 — receiptId={}, policyId={}, userId={}, issueId={}",
                    event.receiptId(), event.policyId(), event.userId(), couponIssue.getId());

            confirmRedisStateAfterCommit(event);

        } catch (DataIntegrityViolationException e) {
            // 2차 방어. 이 catch가 잡는 케이스는 성격이 다른 두 가지가 섞여 있다(이슈 #17):
            //   (a) UNIQUE 제약(uk_policy_user, uk_history_request) 위반 — 극히 드문 동시 진입으로
            //       인한 진짜 "중복". 재시도해도 해소되지 않으므로 재시도만 불필요할 뿐이다.
            //   (b) FK 제약(fk_issue_policy, fk_issue_user) 위반 — policyId/userId가 실제로
            //       존재하지 않는다는 뜻. "중복"이 아니라 데이터 정합성 문제이므로 같은 문구로
            //       뭉뚱그리면 운영 중 원인 파악이 늦어진다(실측: 테스트 유저 시딩 갭을 이 로그
            //       때문에 "정상적인 중복 방어"로 오인해서 한동안 놓칠 뻔함).
            if (isForeignKeyViolation(e)) {
                log.warn("[KafkaConsumer] DB FK 제약 위반 → policyId/userId가 실제로 존재하지 않음(데이터 문제, "
                                + "\"중복\"이 아님) — receiptId={}, policyId={}, userId={}, cause={}",
                        event.receiptId(), event.policyId(), event.userId(), rootCauseMessage(e));
            } else {
                log.info("[KafkaConsumer] DB UNIQUE 제약 위반 → 중복 — receiptId={}, policyId={}, userId={}",
                        event.receiptId(), event.policyId(), event.userId());
            }
            // 여기서 삼키고 정상 리턴하면 안 된다(이슈 #11, 실측으로 확인된 버그): 방금 flush()가
            // 실패하면서 Hibernate가 이 트랜잭션을 이미 rollback-only로 마킹해뒀는데, 예외를 삼킨
            // 채 메서드가 정상 종료되면 Spring 트랜잭션 프록시는 COMMIT을 시도하다가
            // UnexpectedRollbackException을 새로 던진다 — 이게 그대로 KafkaCouponEventConsumer의
            // 폴백 루프로 전파되면 "진짜 실패"로 오인돼 재시도 4번을 낭비하고 DLT까지 잘못 실린다
            // (정상적인 멱등 중복 스킵이었을 뿐인데도). 그래서 같은 예외를 다시 던져 트랜잭션을
            // 예외 기반으로 "정상적으로" 롤백시킨다 — 호출부(KafkaCouponEventConsumer)가 이
            // DataIntegrityViolationException을 별도로 잡아 "정상적인 스킵"으로 처리한다.
            throw e;
        }
        // DataIntegrityViolationException 외 다른 예외(DB 커넥션 오류 등)는
        // 잡지 않고 그대로 위로 throw → DefaultErrorHandler가 받아서 FixedBackOff 재시도
    }

    /**
     * FK 제약 위반인지 판별한다(이슈 #17). MySQL 8은 UNIQUE 위반과 FK 위반 둘 다 Spring이
     * {@link DataIntegrityViolationException}으로 번역하기 때문에 타입만으로는 구분이 안 되고,
     * 근본 원인(대개 {@code java.sql.SQLIntegrityConstraintViolationException})의 메시지로
     * 구분해야 한다 — MySQL은 FK 위반 시 "a foreign key constraint fails" 문구를 포함한다
     * (실측: {@code Cannot add or update a child row: a foreign key constraint fails
     * (`coupon_db`.`coupon_issue`, CONSTRAINT `fk_issue_user` ...)}).
     */
    private boolean isForeignKeyViolation(DataIntegrityViolationException e) {
        String message = rootCauseMessage(e);
        return message != null && message.toLowerCase().contains("foreign key constraint");
    }

    private String rootCauseMessage(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause != null ? cause.getMessage() : e.getMessage();
    }

    /**
     * {@link #confirmRedisState}를 트랜잭션 커밋 이후로 미룬다(이슈 #10).
     *
     * <p>이 클래스는 {@code @Transactional} 메서드 안에서 명시적으로 {@code flush()}를 호출해
     * INSERT를 물리적으로 내보내지만, flush는 "이 트랜잭션 안에서 DB에 반영됨"이지 "커밋
     * 확정"이 아니다 — flush 이후에도 커밋 자체가 실패할 여지가 있다(드물지만 가능: 커넥션 드롭,
     * 격리 수준 충돌 등). 그 좁은 창에서 Redis를 먼저 issued로 바꿔버리면, 커밋이 실패했을 때
     * "Redis는 발급 완료인데 DB엔 없는" 상태가 남는다.
     *
     * <p>{@link TransactionSynchronizationManager#registerSynchronization}으로 커밋 성공 이후
     * 콜백을 등록해서, Redis 반영이 "DB에 실제로 커밋된 건"에 대해서만 실행되도록 한다.
     * 트랜잭션 동기화가 비활성 상태(이론상 이 클래스의 메서드들은 전부 {@code @Transactional}이라
     * 발생하지 않아야 하지만, 방어적으로) 라면 예전처럼 즉시 실행한다.
     */
    private void confirmRedisStateAfterCommit(CouponIssuedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            confirmRedisState(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                confirmRedisState(event);
            }
        });
    }

    /**
     * DB 저장 확정 직후 Redis의 발급 상태를 "처리중(reserved)" → "발급 완료(issued)"로 갱신한다.
     *
     * <p>이 두 키(reserved/issued)는 RedisCouponIssueService·issue_coupon.lua가 재고 예약 시점에
     * 채워두는 것이며, 여기서는 그 뒤처리(확정 반영)만 담당한다 — 재고 관련 원자적 로직은
     * 여기서 새로 만들지 않고, 예약 시점에 이미 정해진 자료구조에 정리만 해준다.
     *
     * <p>실패해도 예외를 던지지 않는다: DB 저장은 이미 커밋됐으므로, 여기서 예외를 던져
     * Kafka 재시도를 유발하면 재시도 시 인박스 체크(existsByRequestId)에 걸려 이 메서드까지
     * 다시 도달하지 못한 채 Redis 상태만 영영 못 고치게 된다. 실패는 로그로만 남기고,
     * 다음 정합성 검증 배치가 드리프트를 잡아내도록 둔다.
     */
    private void confirmRedisState(CouponIssuedEvent event) {
        try {
            redisTemplate.opsForZSet().remove(
                    RedisKeys.couponReserved(event.policyId()), String.valueOf(event.userId()));
            redisTemplate.opsForSet().add(
                    RedisKeys.couponIssued(event.policyId()), String.valueOf(event.userId()));
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Redis 발급 상태(reserved→issued) 갱신 실패 — "
                            + "DB는 이미 저장됨, 다음 정합성 검증 배치에서 드리프트로 잡힐 수 있음. "
                            + "receiptId={}, policyId={}, userId={}",
                    event.receiptId(), event.policyId(), event.userId(), e);
        }
    }

    /** {@link #confirmRedisStateAfterCommit}의 청크 버전 — 동일한 이유(이슈 #10)로 커밋 이후로 미룬다. */
    private void confirmRedisStateBatchAfterCommit(List<CouponIssue> savedIssues) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            confirmRedisStateBatch(savedIssues);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                confirmRedisStateBatch(savedIssues);
            }
        });
    }

    /**
     * {@link #confirmRedisState}의 청크 버전. 건마다 왕복 2번(ZREM, SADD)씩 하는 대신,
     * 청크 전체를 Redis 파이프라인 하나로 묶어 왕복 횟수를 청크당 1번으로 줄인다.
     * 실패 처리 방침은 단건 버전과 동일 — 실패해도 예외를 던지지 않고 로그만 남긴다
     * (DB는 이미 커밋됐고, 드리프트는 다음 정합성 검증 배치가 잡는다).
     */
    private void confirmRedisStateBatch(List<CouponIssue> savedIssues) {
        if (savedIssues.isEmpty()) {
            return;
        }
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (CouponIssue issue : savedIssues) {
                    Long policyId = issue.getCouponPolicy().getId();
                    byte[] member = String.valueOf(issue.getUser().getId()).getBytes(StandardCharsets.UTF_8);
                    connection.zSetCommands().zRem(
                            RedisKeys.couponReserved(policyId).getBytes(StandardCharsets.UTF_8), member);
                    connection.setCommands().sAdd(
                            RedisKeys.couponIssued(policyId).getBytes(StandardCharsets.UTF_8), member);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Redis 발급 상태(reserved→issued) 청크 갱신 실패 — "
                            + "DB는 이미 저장됨, 다음 정합성 검증 배치에서 드리프트로 잡힐 수 있음. size={}",
                    savedIssues.size(), e);
        }
    }
}
