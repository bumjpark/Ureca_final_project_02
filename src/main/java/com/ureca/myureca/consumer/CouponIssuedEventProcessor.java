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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
     * Kafka Consumer 배치 전체를 한 번에 처리한다 (배치 인박스 체크 + 배치 INSERT + Redis 파이프라이닝).
     */
    @Transactional
    public void processBatch(List<CouponIssuedEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        // 1. 배치 인박스 체크: 이미 처리된 receiptId 일괄 조회 (1회 SQL)
        List<String> requestIds = events.stream().map(CouponIssuedEvent::receiptId).toList();
        java.util.Set<String> existing = couponHistoryRepository.findExistingRequestIds(requestIds);

        List<CouponIssuedEvent> newEvents = events.stream()
                .filter(e -> !existing.contains(e.receiptId()))
                .toList();

        if (newEvents.isEmpty()) {
            log.debug("[KafkaConsumer] 배치 전체 중복 스킵 — size={}", events.size());
            return;
        }

        // 2. CouponIssue 및 CouponHistory 엔티티 저장
        List<CouponIssuedEvent> successfullySaved = new java.util.ArrayList<>(newEvents.size());
        for (CouponIssuedEvent event : newEvents) {
            try {
                CouponPolicy couponPolicy = couponPolicyRepository.getReferenceById(event.policyId());
                User user = userRepository.getReferenceById(event.userId());
                CouponIssue couponIssue = new CouponIssue(couponPolicy, user, event.receiptId(), event.issuedAt());
                couponIssueRepository.save(couponIssue);

                CouponHistory history = new CouponHistory(
                        couponIssue,
                        event.receiptId(),
                        HistoryPrevStatus.NONE,
                        IssueStatus.ISSUED,
                        null
                );
                couponHistoryRepository.save(history);
                successfullySaved.add(event);
            } catch (DataIntegrityViolationException e) {
                log.debug("[KafkaConsumer] DB UNIQUE 제약 위반 → 중복 처리로 스킵 — receiptId={}", event.receiptId());
            }
        }

        // 3. Redis 발급 상태 일괄(Pipelining) 갱신: reserved -> issued (단일 TCP 왕복)
        confirmRedisStateBatch(successfullySaved);

        log.debug("[KafkaConsumer] 배치 DB 반영 완료 — 총 {}건 중 {}건 신규 저장", events.size(), successfullySaved.size());
    }

    /**
     * 단건 처리 (단독 호출 시 폴백용)
     */
    @Transactional
    public void processSingle(CouponIssuedEvent event) {
        processBatch(List.of(event));
    }

    /**
     * Redis의 발급 상태(reserved→issued)를 파이프라이닝으로 일괄 갱신한다.
     */
    private void confirmRedisStateBatch(List<CouponIssuedEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try {
            redisTemplate.executePipelined(new org.springframework.data.redis.core.SessionCallback<Object>() {
                @Override
                public Object execute(org.springframework.data.redis.core.RedisOperations operations) {
                    for (CouponIssuedEvent event : events) {
                        operations.opsForZSet().remove(
                                RedisKeys.couponReserved(event.policyId()), String.valueOf(event.userId()));
                        operations.opsForSet().add(
                                RedisKeys.couponIssued(event.policyId()), String.valueOf(event.userId()));
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Redis 발급 상태(reserved→issued) 일괄 갱신 실패 — DB는 저장 완료됨", e);
        }
    }
}
