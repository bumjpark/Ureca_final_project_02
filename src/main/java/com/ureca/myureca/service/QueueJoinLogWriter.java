package com.ureca.myureca.service;

import com.ureca.myureca.domain.queue.QueueStatus;
import com.ureca.myureca.repository.QueueJoinLogRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대기열 진입 순서(FCFS 검증 근거 데이터) 적재 전담. 이슈 #12.
 *
 * <p>처음엔 {@code queue-join-events} Kafka 토픽을 소비하는 전용 컨슈머로 만들었으나, 부하테스트
 * 중 컨슈머가 하나 늘면서 #15(배치 poison 레코드 문제)를 그대로 물려받는 게 확인돼(40건 DLT,
 * 재시도해도 37건 영구 누락) 재설계했다. "감사 로그 하나 남기자고 Kafka 토픽·컨슈머 그룹·배치
 * 에러 핸들링까지 새로 짊어지는 게 과하다"는 판단에 따라 Kafka 컨슈머 경로를 걷어내고,
 * {@link QueueService#joinQueue}가 Lua 호출 직후 이미 확정한 {@code seq}를 그 자리에서 이
 * 클래스로 바로 넘기는 구조로 바꿨다. 전용 {@code @Async} 스레드풀
 * ({@link com.ureca.myureca.config.AsyncConfig#queueJoinLogTaskExecutor()})에서 실행되어
 * join 응답 스레드를 막지 않는다 — 기존 Kafka 발행({@code publishQueueJoinEvent}, 그대로 유지)과
 * 같은 "비동기 비차단" 신뢰성 수준을 유지하면서 새 토픽/컨슈머 그룹/배치 에러 핸들링은 만들지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueJoinLogWriter {

    private final QueueJoinLogRepository queueJoinLogRepository;

    /**
     * @param queueRank 반드시 {@code join_queue.lua}의 {@code seq}(INCR, 절대·불변 순번)여야
     *                  한다 — {@code QueueJoinEvent.rank()}(ZRANK 기반)는 스케줄러가 앞사람을
     *                  계속 ZPOPMIN으로 빼가면서 값이 흔들려 전역 순서 기준으로 쓸 수 없다.
     */
    @Async("queueJoinLogTaskExecutor")
    @Transactional
    public void recordAsync(Long policyId, Long userId, QueueStatus status, Long queueRank, LocalDateTime joinedAt) {
        try {
            queueJoinLogRepository.insertIgnore(policyId, userId, status.name(), queueRank, joinedAt);
        } catch (Exception e) {
            // 감사 로그 성격의 부가 데이터라 이 적재 실패가 join 응답 자체를 막으면 안 된다
            // (이미 @Async라 호출자에게 전파되지도 않지만, 방어적으로 여기서도 끝까지 삼킨다).
            log.warn("queue_join_log 적재 실패 (FCFS 검증 근거 데이터 누락, join 자체는 정상 처리됨) "
                    + "— policyId={}, userId={}, queueRank={}", policyId, userId, queueRank, e);
        }
    }
}
