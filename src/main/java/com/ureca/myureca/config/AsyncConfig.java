package com.ureca.myureca.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 검증 배치 비동기 실행 전용 스레드풀.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "verificationTaskExecutor")
    public Executor verificationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("verification-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * {@code queue_join_log} 적재 전용 스레드풀(이슈 #12). {@code QueueService.joinQueue()}가
     * join 응답 스레드를 막지 않고 대기열 진입 순번(seq)을 이 풀에 넘겨 비동기로 적재한다.
     * 큐 유입이 몰릴 때(대기열이 바로 이 상황) 풀이 가득 차면, 별도 큐잉으로 지연시키기보다
     * 호출 스레드가 직접 처리(CallerRunsPolicy)하게 해서 유실 없이 자연스러운 배압을 건다.
     */
    @Bean(name = "queueJoinLogTaskExecutor")
    public Executor queueJoinLogTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("queue-join-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
