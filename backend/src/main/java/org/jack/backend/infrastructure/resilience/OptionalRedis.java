package org.jack.backend.infrastructure.resilience;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OptionalRedis {
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final AtomicLong retryAt=new AtomicLong();
    public OptionalRedis(StringRedisTemplate redis,Clock clock){this.redis=redis;this.clock=clock;}
    public String get(String key) {
        if(clock.millis()<retryAt.get())return null;
        try{return redis.opsForValue().get(key);}catch(RuntimeException unavailable){retryAt.set(clock.millis()+20000);return null;}
    }
    public void put(String key,String value,Duration ttl) {
        if(clock.millis()<retryAt.get())return;
        try{redis.opsForValue().set(key,value,ttl);}catch(RuntimeException unavailable){retryAt.set(clock.millis()+20000);}
    }
    public boolean available() {
        if(clock.millis()<retryAt.get())return false;
        try{redis.hasKey("investlens:cache:health");return true;}catch(RuntimeException unavailable){retryAt.set(clock.millis()+20000);return false;}
    }
}
