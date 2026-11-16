package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.utils.BookingAdmissionRedis;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRules;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@EnabledIfSystemProperty(named = "booking.redis-integration", matches = "true")
class BookingAdmissionRedisIntegrationTest {
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory =
                new LettuceConnectionFactory(
                        new RedisStandaloneConfiguration("127.0.0.1", 16389),
                        LettuceClientConfiguration.builder()
                                .commandTimeout(Duration.ofSeconds(2))
                                .shutdownTimeout(Duration.ZERO)
                                .build());
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @Test
    void staleSqlSnapshotCannotResetPreviouslyReservedStockAndOldCallbackCannotDeleteNewOwner() {
        String prefix = "test:admission:" + UUID.randomUUID() + ":";
        BookingRedisProperties cache = new BookingRedisProperties();
        cache.setNamespace(prefix);
        BookingAdmissionRedis guard =
                new BookingAdmissionRedis(redis, cache, new BookingControlsProperties());
        BookingRequestTask first = task("first", 7L);
        BookingRequestTask second = task("second", 8L);
        String stock = prefix + "booking:async:stock:100";
        String user = prefix + "booking:async:user:" + first.getBookingDate() + ":7";
        try {
            guard.reserve(first, 1);
            assertThrows(BookingException.class, () -> guard.reserve(second, 1));
            assertEquals("0", redis.opsForValue().get(stock));
            assertEquals(0, guard.remaining(100L, 1));
            redis.opsForValue().set(user, "P:new-owner", 120, TimeUnit.SECONDS);
            guard.release(first);
            assertEquals("P:new-owner", redis.opsForValue().get(user));
            assertFalse(redis.hasKey(stock));
        } finally {
            redis.delete(Arrays.asList(stock, user));
        }
    }

    @Test
    void terminalResultIsScopedToOwnerAndReadsDoNotExtendThreeDays() {
        String prefix = "test:admission:" + UUID.randomUUID() + ":";
        BookingRedisProperties cache = new BookingRedisProperties();
        cache.setNamespace(prefix);
        BookingAdmissionRedis guard =
                new BookingAdmissionRedis(redis, cache, new BookingControlsProperties());
        BookingRequestTask task = task("result", 7L);
        task.setStatus("SUCCEEDED");
        task.setCompleteTime(BookingRules.now());
        String key = prefix + "booking:request:result:7:result";
        try {
            guard.cacheResult(task);
            assertTrue(redis.getExpire(key) >= 259197);
            assertNull(guard.cachedResult(8L, "result"));
            assertEquals("SUCCEEDED", guard.cachedResult(7L, "result").getState());
            redis.expire(key, 60, TimeUnit.SECONDS);
            guard.cacheResult(task);
            assertTrue(redis.getExpire(key) <= 60);
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void orphanedStockExpiresWithinProcessingWindow() {
        String prefix = "test:admission:" + UUID.randomUUID() + ":";
        BookingRedisProperties cache = new BookingRedisProperties();
        cache.setNamespace(prefix);
        BookingControlsProperties controls = new BookingControlsProperties();
        BookingAdmissionRedis guard = new BookingAdmissionRedis(redis, cache, controls);
        BookingRequestTask orphan = task("orphan", 71L);
        BookingRequestTask next = task("next", 72L);
        String stock = prefix + "booking:async:stock:100";
        String marker = prefix + "booking:async:user:" + orphan.getBookingDate() + ":71";
        String nextMarker = prefix + "booking:async:user:" + orphan.getBookingDate() + ":72";
        try {
            guard.reserve(orphan, 1);
            assertTrue(
                    redis.getExpire(stock) <= controls.getProcessingTtlSeconds(),
                    "没有SQL记录的预占也必须有短期恢复期限");
            // 模拟Redis自然过期，无需让测试等待120秒。
            redis.expire(stock, 0, TimeUnit.SECONDS);
            redis.expire(marker, 0, TimeUnit.SECONDS);
            assertDoesNotThrow(() -> guard.reserve(next, 1));
        } finally {
            redis.delete(Arrays.asList(stock, marker, nextMarker));
        }
    }

    static BookingRequestTask task(String id, Long userId) {
        BookingRequestTask task = new BookingRequestTask();
        task.setId(id);
        task.setUserId(userId);
        task.setSlotId(100L);
        task.setBookingDate(BookingRules.now().toLocalDate().plusDays(1));
        return task;
    }
}
