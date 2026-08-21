package com.ureca.myureca.service;

import com.ureca.myureca.support.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ActiveTokenService {

    private final StringRedisTemplate redisTemplate;

    public boolean consume(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        Boolean deleted = redisTemplate.delete(RedisKeys.activeToken(token));
        return Boolean.TRUE.equals(deleted);
    }
}
