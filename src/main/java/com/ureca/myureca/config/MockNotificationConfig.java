package com.ureca.myureca.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

/**
 * Mock 알림 API 전용 비동기 실행기.
 *
 * <p>이 프로젝트가 계속 신경 써온 문제(20,000 동시 요청에서 스레드 풀 고갈)를
 * Mock API 자신이 재현하지 않기 위한 설정이다. 외부 API 호출을 흉내 낸 인위적 지연을
 * 요청을 받은 스레드에서 그대로 sleep 하면, 나중에 이 Mock이 실제 발급/사용
 * 흐름에 동기 연결됐을 때 스레드 풀이 그대로 고갈될 수 있다. 그래서 지연 시뮬레이션은
 * 별도의 가상 스레드에서 실행하고, 요청을 받은 스레드는 즉시 풀로 돌아가게 한다.</p>
 */
@Configuration
public class MockNotificationConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService mockNotificationExecutorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Spring MVC의 {@code WebAsyncTask}가 요구하는 {@code AsyncTaskExecutor} 형태로 감싼 것. */
    @Bean
    public AsyncTaskExecutor mockNotificationAsyncTaskExecutor(ExecutorService mockNotificationExecutorService) {
        return new TaskExecutorAdapter(mockNotificationExecutorService);
    }
}
