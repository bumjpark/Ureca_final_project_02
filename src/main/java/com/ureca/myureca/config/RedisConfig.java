package com.ureca.myureca.config;

import com.ureca.myureca.service.QueueSseService;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    /**
     * SSE ADMITTED 푸시(이슈 #23)를 위한 Pub/Sub 리스너 컨테이너. {@link QueueSseService}를
     * {@code sse:admitted} 채널의 구독자로 등록해, 어느 인스턴스가 발행하든 전체 인스턴스가
     * 메시지를 받아 각자 로컬 SSE emitter에 유저가 있으면 push하게 한다.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, QueueSseService queueSseService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                queueSseService, new org.springframework.data.redis.listener.ChannelTopic(QueueSseService.ADMITTED_CHANNEL));
        return container;
    }

    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationBuilderCustomizer() {
        ClientOptions clientOptions = ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofMillis(500))
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(1)))
                .build();
        return builder -> builder.clientOptions(clientOptions);
    }

    @Bean
    public RedisScript<Long> issueCouponScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/issue_coupon.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** join_queue.lua: {statusCode, rank, queueLen} 배열 반환 */
    @SuppressWarnings("unchecked")
    @Bean
    public RedisScript<List<Long>> joinQueueScript() {
        DefaultRedisScript script = new DefaultRedisScript();
        script.setLocation(new ClassPathResource("scripts/join_queue.lua"));
        script.setResultType(List.class);
        return script;
    }

    /**
     * admit_batch.lua: 이번 틱에 입장시킬 userId 문자열 배열을 반환한다(재고는 안 건드림 —
     * 재고 차감은 여전히 {@link #issueCouponScript}만의 책임). {@code 재고 - pending ZSET 크기}
     * 만큼만 뽑아, 아직 발급 확정 안 된 사람들과 새 입장자가 여러 틱에 걸쳐 같은 재고를
     * 놓고 경쟁하는 것을 막는다(2026-08-30 FCFS 역전 조사).
     */
    @SuppressWarnings("unchecked")
    @Bean
    public RedisScript<List<String>> admitBatchScript() {
        DefaultRedisScript script = new DefaultRedisScript();
        script.setLocation(new ClassPathResource("scripts/admit_batch.lua"));
        script.setResultType(List.class);
        return script;
    }

    /** consume_token.lua: 1(성공) / 0(토큰 없음) / -1(userId 불일치) */
    @Bean
    public RedisScript<Long> consumeTokenScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/consume_token.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** get_queue_status.lua: {status, token, rank} 배열 반환 */
    @SuppressWarnings("unchecked")
    @Bean
    public RedisScript<List<String>> getQueueStatusScript() {
        DefaultRedisScript script = new DefaultRedisScript();
        script.setLocation(new ClassPathResource("scripts/get_queue_status.lua"));
        script.setResultType(List.class);
        return script;
    }

    /** recovery_finalize.lua: Redis 재구성(E) 마무리 — stock/reserved/issued를 원자적으로 교체 */
    @Bean
    public RedisScript<Long> recoveryFinalizeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/recovery_finalize.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** renew_lock.lua: 소유권(토큰) 확인 후 락 TTL 연장. 1=성공, 0=이미 락을 잃음 */
    @Bean
    public RedisScript<Long> renewLockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/renew_lock.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** release_lock.lua: 소유권(토큰) 확인 후 락 해제(compare-and-delete) */
    @Bean
    public RedisScript<Long> releaseLockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/release_lock.lua"));
        script.setResultType(Long.class);
        return script;
    }
}

