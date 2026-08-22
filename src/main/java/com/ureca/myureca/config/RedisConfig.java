package com.ureca.myureca.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

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
}
