package com.ureca.myureca.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

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

