package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.notification.MockNotificationBulkJob;
import com.ureca.myureca.domain.notification.MockNotificationLog;
import com.ureca.myureca.dto.request.KakaoNotificationRequest;
import com.ureca.myureca.dto.response.KakaoNotificationResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.MockNotificationBulkJobRepository;
import com.ureca.myureca.repository.MockNotificationLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MockNotificationServiceTest {

    @Mock
    private MockNotificationLogRepository mockNotificationLogRepository;

    @Mock
    private MockNotificationBulkJobRepository mockNotificationBulkJobRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponPolicyRepository couponPolicyRepository;

    @Mock
    private AsyncTaskExecutor mockNotificationAsyncTaskExecutor;

    private MockNotificationService service;

    @BeforeEach
    void setUp() {
        service = new MockNotificationService(
                mockNotificationLogRepository, mockNotificationBulkJobRepository,
                couponIssueRepository, couponPolicyRepository, mockNotificationAsyncTaskExecutor);
        // 실제 지연을 없애고(테스트 속도), 강제/확률 실패가 아닌 한 항상 성공하게 고정
        ReflectionTestUtils.setField(service, "minLatencyMs", 0L);
        ReflectionTestUtils.setField(service, "maxLatencyMs", 0L);
        ReflectionTestUtils.setField(service, "failureRate", 0.0);
        // JPA IDENTITY 채번을 흉내: 처음 저장될 때만 id를 부여(실제 save처럼 이미 있으면 그대로 둠)
        lenient().when(mockNotificationBulkJobRepository.save(any())).thenAnswer(inv -> {
            MockNotificationBulkJob job = inv.getArgument(0);
            if (job.getId() == null) {
                ReflectionTestUtils.setField(job, "id", 99L);
            }
            return job;
        });
    }

    @Test
    void 단건_발송_성공시_SENT_로그를_저장한다() {
        KakaoNotificationResponse response = service.send(
                new KakaoNotificationRequest(1L, "TPL", "메시지"), false);

        assertThat(response.status()).isEqualTo("SENT");

        ArgumentCaptor<MockNotificationLog> captor = ArgumentCaptor.forClass(MockNotificationLog.class);
        verify(mockNotificationLogRepository).save(captor.capture());
        MockNotificationLog saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SENT");
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getCouponPolicyId()).isNull(); // 단건 발송은 정책과 무관
    }

    @Test
    void simulateFailure가_true면_FAILED_로그를_저장한다() {
        KakaoNotificationResponse response = service.send(
                new KakaoNotificationRequest(1L, "TPL", "메시지"), true);

        assertThat(response.status()).isEqualTo("FAILED");
        ArgumentCaptor<MockNotificationLog> captor = ArgumentCaptor.forClass(MockNotificationLog.class);
        verify(mockNotificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void 존재하지_않는_정책으로_일괄발송하면_예외를_던진다() {
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendBulkByPolicy(999L, "TPL", "메시지"))
                .isInstanceOf(CouponPolicyNotFoundException.class);

        verify(mockNotificationAsyncTaskExecutor, never()).execute(any());
    }

    @Test
    void 일괄발송은_작업을_IN_PROGRESS로_만들고_대상자_수를_즉시_반환한다() {
        CouponPolicy policy = new CouponPolicy(
                "테스트", CouponType.FIXED, 1000, 100, LocalDateTime.now().minusHours(1), null);
        ReflectionTestUtils.setField(policy, "id", 1L);
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(policy));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(10L, 20L, 30L));

        MockNotificationService.BulkSendResult result = service.sendBulkByPolicy(1L, "TPL", "메시지");

        assertThat(result.targetCount()).isEqualTo(3);
        assertThat(result.jobId()).isEqualTo(99L);

        // 작업 행 자체는 시작 시점에 IN_PROGRESS로 즉시 저장된다(실제 발송 여부와 무관)
        ArgumentCaptor<MockNotificationBulkJob> jobCaptor = ArgumentCaptor.forClass(MockNotificationBulkJob.class);
        verify(mockNotificationBulkJobRepository, times(1)).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(jobCaptor.getValue().getTargetCount()).isEqualTo(3);

        // 실제 발송 루프는 이 시점까지 실행되지 않아야 한다(비동기로 넘겼을 뿐)
        verify(mockNotificationLogRepository, never()).save(any());
    }

    @Test
    void 일괄발송_작업을_실행하면_건별_로그와_작업_진행률이_함께_갱신되고_완료_처리된다() {
        CouponPolicy policy = new CouponPolicy(
                "테스트", CouponType.FIXED, 1000, 100, LocalDateTime.now().minusHours(1), null);
        ReflectionTestUtils.setField(policy, "id", 1L);
        when(couponPolicyRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(policy));
        when(couponIssueRepository.findUserIdsByCouponPolicyId(1L)).thenReturn(List.of(10L, 20L, 30L));

        service.sendBulkByPolicy(1L, "TPL", "메시지");

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockNotificationAsyncTaskExecutor, times(1)).execute(taskCaptor.capture());

        // 넘겨받은 작업을 직접 실행 — 대상자 3명 전원에게 로그가 남고, 작업 행은 3(성공)/3(완료)/COMPLETED가 된다
        taskCaptor.getValue().run();

        verify(mockNotificationLogRepository, times(3)).save(any());

        ArgumentCaptor<MockNotificationBulkJob> jobCaptor = ArgumentCaptor.forClass(MockNotificationBulkJob.class);
        // 시작 시 1번 + 건마다 1번(3) + 완료 처리 1번 = 총 5번 저장
        verify(mockNotificationBulkJobRepository, times(5)).save(jobCaptor.capture());
        MockNotificationBulkJob finalState = jobCaptor.getValue();
        assertThat(finalState.getStatus()).isEqualTo("COMPLETED");
        assertThat(finalState.getSentCount()).isEqualTo(3);
        assertThat(finalState.getFailedCount()).isEqualTo(0);
        assertThat(finalState.getCompletedAt()).isNotNull();
    }
}
