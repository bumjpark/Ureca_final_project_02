package com.ureca.myureca.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AsyncConfigTest {

    @Test
    void 정책_300개_규모의_동시_제출도_거부_없이_전부_받는다() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().verificationTaskExecutor();
        try {
            int taskCount = 300;
            CountDownLatch latch = new CountDownLatch(taskCount);
            AtomicInteger rejected = new AtomicInteger(0);

            for (int i = 0; i < taskCount; i++) {
                submit(executor, latch, rejected);
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);

            assertThat(rejected.get()).isZero();
            assertThat(completed).isTrue();
        } finally {
            executor.shutdown();
        }
    }

    private void submit(Executor executor, CountDownLatch latch, AtomicInteger rejected) {
        try {
            executor.execute(() -> {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        } catch (TaskRejectedException e) {
            rejected.incrementAndGet();
            latch.countDown();
        }
    }
}
