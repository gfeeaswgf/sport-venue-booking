package com.hmdp.utils;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.dto.IdempotencyRecord;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 封装请求限流及幂等状态，和库存预占 BookingRedisGuard 分开管理。 */
@Slf4j
@Component
public class BookingRequestRedis {
    private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>();
    private static final DefaultRedisScript<List> IDEMPOTENCY = new DefaultRedisScript<>();

    static {
        RATE.setLocation(new ClassPathResource("booking-rate-limit.lua"));
        RATE.setResultType(Long.class);
        IDEMPOTENCY.setLocation(new ClassPathResource("booking-idempotency.lua"));
        IDEMPOTENCY.setResultType(List.class);
    }

    private final StringRedisTemplate redis;
    private final BookingControlsProperties controls;
    private final BookingRedisProperties cache;

    public BookingRequestRedis(
            StringRedisTemplate redis,
            BookingControlsProperties controls,
            BookingRedisProperties cache) {
        this.redis = redis;
        this.controls = controls;
        this.cache = cache;
    }

    public void checkRate(Long userId, Long slotId, String requestId) {
        // 1.每个许可由 Redis 自身计时并自动过期，不依赖应用服务器的墙钟。
        try {
            // 2.组合启用的限流维度，0表示未开启接口或场次限流。
            List<String> keys = new ArrayList<>();
            List<String> arguments = new ArrayList<>();
            arguments.add(String.valueOf(controls.getWindowMillis()));
            arguments.add(requestId);
            addLimit(keys, arguments, "user:" + userId, controls.getUserLimit());
            addLimit(keys, arguments, "api", controls.getApiLimit());
            if (slotId != null) {
                addLimit(keys, arguments, "slot:" + slotId, controls.getSlotLimit());
            }

            // 3.清理、判断和登记在一个 Lua 中完成，第三次不会穿透到业务层。
            Long result = redis.execute(RATE, keys, arguments.toArray());
            if (!Long.valueOf(0).equals(result)) {
                throw new BookingControlException("LIMITED", "请求过于频繁，每秒最多提交两次，请稍后重试");
            }
        } catch (BookingControlException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    public IdempotencyRecord begin(Long userId, String key, String fingerprint, String owner) {
        try {
            List result = execute("begin", userId, key, fingerprint, owner, "");
            if (result == null || result.isEmpty()) {
                throw new IllegalStateException("Redis未返回幂等状态");
            }
            String state = String.valueOf(result.get(0));
            String orderId = result.size() > 1 ? String.valueOf(result.get(1)) : null;
            return new IdempotencyRecord(state, orderId);
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    public boolean complete(
            Long userId, String key, String fingerprint, String owner, String orderId) {
        List result = execute("complete", userId, key, fingerprint, owner, orderId);
        return result != null && !result.isEmpty() && "OK".equals(result.get(0));
    }

    public boolean release(Long userId, String key, String fingerprint, String owner) {
        List result = execute("release", userId, key, fingerprint, owner, "");
        return result != null && !result.isEmpty() && "OK".equals(result.get(0));
    }

    private List execute(
            String action,
            Long userId,
            String key,
            String fingerprint,
            String owner,
            String orderId) {
        String redisKey = cache.getNamespace() + "booking:idempotent:CREATE:" + userId + ":" + key;
        return redis.execute(
                IDEMPOTENCY,
                Collections.singletonList(redisKey),
                action,
                fingerprint,
                owner,
                String.valueOf(controls.getProcessingTtlSeconds()),
                String.valueOf(controls.getSuccessTtlSeconds()),
                orderId);
    }

    private void addLimit(List<String> keys, List<String> arguments, String dimension, int limit) {
        if (limit > 0) {
            keys.add(cache.getNamespace() + "booking:rate:CREATE:" + dimension);
            arguments.add(String.valueOf(limit));
        }
    }

    private BookingControlException unavailable(RuntimeException failure) {
        log.warn("预约请求控制Redis不可用 exception={}", failure.getClass().getSimpleName());
        return new BookingControlException("REDIS_UNAVAILABLE", "预约服务暂不可用，请稍后重试");
    }
}
