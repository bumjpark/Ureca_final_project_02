package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.ureca.myureca.domain.queue.QueueStatus;
import com.ureca.myureca.repository.QueueJoinLogRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * queue_join_log 적재(이슈 #12) 단위 테스트. {@code queueRank}에 반드시 seq(전역 절대 순번)가
 * 들어가는지, INSERT IGNORE라 재조인/경합 상황에서도 예외가 join 응답을 막지 않는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueJoinLogWriterTest {

    @Mock
    private QueueJoinLogRepository queueJoinLogRepository;

    @InjectMocks
    private QueueJoinLogWriter writer;

    private final Long POLICY_ID = 1L;
    private final Long USER_ID = 42L;

    @Test
    void seq를_queueRank로_그대로_전달해_적재한다() {
        LocalDateTime joinedAt = LocalDateTime.now();

        writer.recordAsync(POLICY_ID, USER_ID, QueueStatus.WAITING, 123L, joinedAt);

        verify(queueJoinLogRepository).insertIgnore(
                eq(POLICY_ID), eq(USER_ID), eq("WAITING"), eq(123L), eq(joinedAt));
    }

    @Test
    void ADMITTED_상태도_그대로_저장된다() {
        LocalDateTime joinedAt = LocalDateTime.now();

        writer.recordAsync(POLICY_ID, USER_ID, QueueStatus.ADMITTED, 1L, joinedAt);

        verify(queueJoinLogRepository).insertIgnore(
                eq(POLICY_ID), eq(USER_ID), eq("ADMITTED"), eq(1L), eq(joinedAt));
    }

    @Test
    void 재조인_등으로_INSERT_IGNORE가_예외를_던져도_삼키고_전파하지_않는다() {
        // INSERT IGNORE는 UNIQUE 위반을 원래 조용히 무시하지만, 커넥션 장애 등 다른 이유로
        // 예외가 나더라도 이 메서드는 @Async라 호출자(join 응답 스레드)에 영향이 없어야 한다.
        doThrow(new DataIntegrityViolationException("boom"))
                .when(queueJoinLogRepository).insertIgnore(any(), any(), any(), any(), any());

        assertThatCode(() -> writer.recordAsync(POLICY_ID, USER_ID, QueueStatus.WAITING, 1L, LocalDateTime.now()))
                .doesNotThrowAnyException();
    }
}
