package com.ureca.myureca.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 배치 실행용 스레드풀 설정.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * 현재 {@code @Scheduled} <b>메서드</b> 7개 + 여유 3.
     *
     * <p>예전 주석은 "배치 5개 + 여유 1"이라 6으로 잡혀 있었는데, 그 5는 <b>클래스</b> 수였다 —
     * {@code RedisAutoRecoveryScheduler} 하나가 서로 다른 주기의 메서드 3개
     * ({@code recoverMissingStock} 5초 / {@code reconcileReservedDrift} 10초 /
     * {@code detectStaleReservedDrift} 60초)를 갖고 있어서 실제 스케줄 대상은 7개다. 즉 여유가
     * 1이 아니라 -1이었고, 배치 하나가 길어지면 다른 배치가 통째로 밀렸다.
     *
     * <p>실제로 2026-08-31 실측에서 {@code detectStaleReservedDrift}가 미아 예약 11,000건을
     * 등록하며 스레드를 2분간 점유하자 {@code recoverMissingStock}이 밀렸고, 신규 정책의 Redis
     * 재고 키가 초기화되지 않아 발급 요청 20,000건이 전량 500으로 실패했다. 등록 로직 자체는
     * 배치로 고쳤지만({@code VerificationAsyncTrigger.registerRedisOnlyDrift}), 한 배치가 느려도
     * 다른 배치가 굶지 않도록 풀 자체에도 여유를 둔다.
     *
     * <p>배치(메서드 기준)를 추가하면 이 값도 함께 올릴 것.
     */
    @Value("${spring.task.scheduling.pool.size:10}")
    private int poolSize;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("scheduled-");
        // 종료 시 진행 중인 배치를 끝까지 기다린다. RedisRecoveryService가 분산 락을 쥔 채
        // 재구성 중일 수 있어, 중간에 끊기면 락이 TTL 만료까지 남는다(하트비트가 멈춰 30초 내
        // 자연 해제되긴 하지만, 정상 종료 경로에서는 깔끔하게 끝내고 나가는 편이 낫다).
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        // 취소된 작업을 큐에 남겨두지 않는다(장기 실행 시 큐 누적 방지).
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
