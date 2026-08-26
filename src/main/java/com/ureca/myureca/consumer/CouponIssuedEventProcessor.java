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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka Consumer가 수신한 CouponIssuedEvent를 DB에 반영하는 단위 처리기.
 *
 * <p>DB 반영 성공 시 Redis의 발급 상태(reserved→issued)도 함께 정리한다({@link #confirmRedisState}).
 * 그렇게 하지 않으면 reserved가 영구히 쌓이고 issued가 계속 비어있어, 정합성 검증 배치가
 * 정상 발급 건까지 전부 불일치로 오판하게 된다.
 *
 * <p>KafkaCouponEventConsumer(@KafkaListener)로부터 이벤트 하나씩 순차 호출되며,
 * 이 메서드 단위로 트랜잭션이 분리된다. 배치 중 하나가 실패해도 이미 커밋된 이전 이벤트는
 * 롤백되지 않으며, 실패한 이벤트만 DefaultErrorHandler의 재시도 대상이 된다.
 *
 * <p>이 Bean이 @KafkaListener와 별도로 분리된 이유:
 * Spring AOP 프록시는 self-invoke(같은 클래스 내부 메서드 호출)에서 동작하지 않기 때문에
 * @KafkaListener와 @Transactional이 같은 클래스에 있으면 트랜잭션이 적용되지 않는다.
 *
 * <p>JDBC Batch Insert 주의:
 * CouponIssue, CouponHistory 모두 @GeneratedValue(strategy=IDENTITY)를 사용하므로,
 * Hibernate가 INSERT 직후 생성된 ID를 즉시 조회해야 하는 IDENTITY 전략 특성상
 * batch insert가 무력화된다(Hibernate batch 문서 참고).
 * 성능 개선이 필요하다면 SEQUENCE 전략으로 전환 후 hibernate.jdbc.batch_size를 설정해야 한다.
 * 현재는 정합성 우선 설계이므로 엔티티 전략을 변경하지 않는다.
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
     * 이벤트 하나를 처리하여 coupon_issue, coupon_history에 INSERT한다.
     *
     * <p>예외 처리 갈래:
     * <ul>
     *   <li>{@link DataIntegrityViolationException} → catch 후 INFO 로그만 남기고 정상 종료.
     *       DB UNIQUE 제약(request_id)을 통한 2차 멱등성 방어 결과이므로 재시도 불필요.</li>
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

            log.info("[KafkaConsumer] 쿠폰 발급 DB 반영 완료 — receiptId={}, policyId={}, userId={}, issueId={}",
                    event.receiptId(), event.policyId(), event.userId(), couponIssue.getId());

            confirmRedisState(event);

        } catch (DataIntegrityViolationException e) {
            // 2차 방어: 극히 드문 동시 진입으로 인한 UNIQUE 제약 위반
            // 재시도해도 제약이 해소되지 않으므로 예외를 삼키고 정상 종료한다.
            log.info("[KafkaConsumer] DB UNIQUE 제약 위반 → 중복 처리로 스킵 (정상 종료) — receiptId={}, policyId={}, userId={}",
                    event.receiptId(), event.policyId(), event.userId());
        }
        // DataIntegrityViolationException 외 다른 예외(DB 커넥션 오류 등)는
        // 잡지 않고 그대로 위로 throw → DefaultErrorHandler가 받아서 FixedBackOff 재시도
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
}
