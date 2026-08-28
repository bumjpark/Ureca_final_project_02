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

    /** 현재 {@code @Scheduled} 배치 4개(QueueAdmission, InfraHealthMonitor, RedisAutoRecovery,
     *  ReconciliationAutoRetry) + 여유 1. 배치를 추가하면 이 값도 함께 올릴 것. */
    @Value("${spring.task.scheduling.pool.size:5}")
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
