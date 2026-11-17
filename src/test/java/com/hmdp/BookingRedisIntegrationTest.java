package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;

import com.hmdp.config.BookingRedisProperties;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueSlot;
import com.hmdp.utils.BookingCache;
import com.hmdp.utils.BookingRules;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅连接显式测试地址；不加载 Spring Boot 或业务连接配置。 */
@EnabledIfSystemProperty(named = "booking.redis-integration", matches = "true")
class BookingRedisIntegrationTest {
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    BookingRedisProperties properties;
    BookingCache cache;
    VenueSlot slot;
    String prefix;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        System.getProperty("booking.test-redis-host", "127.0.0.1"),
                        Integer.getInteger("booking.test-redis-port", 16389));
        String password = System.getenv("BOOKING_TEST_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) {
            config.setPassword(RedisPassword.of(password));
        }
        factory =
                new LettuceConnectionFactory(
                        config,
                        LettuceClientConfiguration.builder()
                                .commandTimeout(Duration.ofSeconds(2))
                                .shutdownTimeout(Duration.ZERO)
                                .build());
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        try (RedisConnection connection = factory.getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void setup() {
        prefix = "test:booking-redis:" + UUID.randomUUID() + ":";
        properties = new BookingRedisProperties();
        properties.setNamespace(prefix);
        cache = new BookingCache(redis, properties);
        slot =
                new VenueSlot()
                        .setId(100L)
                        .setCourtId(10L)
                        .setBookingDate(BookingRules.now().toLocalDate().plusDays(1))
                        .setStartTime(LocalTime.of(14, 30))
                        .setEndTime(LocalTime.of(16, 10))
                        .setCapacity(1)
                        .setBookedCount(0)
                        .setEnabled(true);
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            finish(false);
        }
        List<String> keys = new ArrayList<>();
        try (RedisConnection connection = factory.getConnection();
                Cursor<byte[]> cursor =
                        connection.scan(
                                ScanOptions.scanOptions().match(prefix + "*").count(100).build())) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    void begin() {
        TransactionSynchronizationManager.initSynchronization();
    }

    void finish(boolean commit) {
        try {
            if (commit) {
                TransactionSynchronizationUtils.triggerAfterCommit();
            }
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    commit
                            ? TransactionSynchronization.STATUS_COMMITTED
                            : TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void catalogChangesDeleteFixedQueryKeysInBatchesAfterCommit() {
        for (int i = 0; i < 125; i++) {
            redis.opsForValue().set(prefix + "cache:booking:venue:" + i, "old");
        }
        redis.opsForValue().set(prefix + "login:token:test", "login");
        redis.opsForValue().set(prefix + "booking:stock:100", "1");
        redis.opsForValue().set(prefix + "other:cache:booking:venue:1", "unrelated");
        begin();
        cache.catalogChanged();
        assertEquals("old", redis.opsForValue().get(prefix + "cache:booking:venue:1"));
        finish(true);
        for (int i = 0; i < 125; i++) {
            assertFalse(redis.hasKey(prefix + "cache:booking:venue:" + i));
        }
        assertEquals("login", redis.opsForValue().get(prefix + "login:token:test"));
        assertEquals("1", redis.opsForValue().get(prefix + "booking:stock:100"));
        assertEquals("unrelated", redis.opsForValue().get(prefix + "other:cache:booking:venue:1"));
        cache.get("venue:1", Venue.class, () -> new Venue().setName("新场馆"));
        assertTrue(redis.hasKey(prefix + "cache:booking:venue:1"));
        assertEquals(-1L, redis.getExpire(prefix + "cache:booking:venue:1"));
        assertEquals("新场馆", cache.get("venue:1", Venue.class, () -> null).getName());
    }

    @Test
    void redisCacheRoundTripsDatesAndInvalidatesPreviouslyEmptySlots() {
        AtomicInteger loads = new AtomicInteger();
        assertTrue(
                cache.getSlots(
                                10L,
                                slot.getBookingDate(),
                                () -> {
                                    loads.incrementAndGet();
                                    return Collections.emptyList();
                                })
                        .isEmpty());
        assertTrue(
                cache.getSlots(
                                10L,
                                slot.getBookingDate(),
                                () -> {
                                    loads.incrementAndGet();
                                    return Collections.singletonList(slot);
                                })
                        .isEmpty());
        assertEquals(1, loads.get());
        begin();
        cache.slotsChanged(10L, slot.getBookingDate());
        finish(true);
        cache.getSlots(10L, slot.getBookingDate(), () -> Collections.singletonList(slot));
        VenueSlot hit = cache.getSlots(10L, slot.getBookingDate(), Collections::emptyList).get(0);
        assertEquals(slot.getBookingDate(), hit.getBookingDate());
        assertEquals(slot.getStartTime(), hit.getStartTime());
        assertEquals(slot.getEndTime(), hit.getEndTime());
        assertEquals(1, hit.getCapacity());
    }
}
