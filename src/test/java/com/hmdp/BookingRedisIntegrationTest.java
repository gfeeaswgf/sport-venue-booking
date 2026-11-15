package com.hmdp;

import com.hmdp.config.BookingRedisProperties;
import com.hmdp.entity.BookingOrder;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueSlot;
import com.hmdp.utils.BookingCache;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRedisGuard;
import com.hmdp.utils.BookingRules;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅连接显式测试地址；不加载 Spring Boot 或业务连接配置。 */
@EnabledIfSystemProperty(named = "booking.redis-integration", matches = "true")
class BookingRedisIntegrationTest {
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    BookingRedisProperties properties;
    BookingRedisGuard guard;
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
        guard = new BookingRedisGuard(redis, properties);
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

    String stock() {
        return prefix + RedisConstants.BOOKING_STOCK_KEY + slot.getId();
    }

    String user(Long id) {
        return prefix + RedisConstants.BOOKING_USER_KEY + slot.getBookingDate() + ":" + id;
    }

    BookingOrder order(String id) {
        return new BookingOrder()
                .setId(id)
                .setSlotId(slot.getId())
                .setUserId(7L)
                .setBookingDate(slot.getBookingDate());
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
    void rateLimitIsAtomicAndHasBoundedLifetime() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger accepted = new AtomicInteger();
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(
                        pool.submit(
                                () -> {
                                    try {
                                        guard.checkRate(7L);
                                        accepted.incrementAndGet();
                                    } catch (BookingException expected) {
                                    }
                                }));
            }
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accepted.get());
        Long ttl = redis.getExpire(prefix + RedisConstants.BOOKING_RATE_KEY + 7L);
        assertTrue(ttl >= 0 && ttl <= 2);
        guard.checkRate(8L);
    }

    @Test
    void commitConfirmsOwnedMarkerAndCancellationOnlyChangesRedisAfterCommit() {
        begin();
        guard.reserve(slot, 7L, "first");
        assertEquals("0", redis.opsForValue().get(stock()));
        assertEquals("P:first", redis.opsForValue().get(user(7L)));
        finish(true);
        assertEquals("C:first", redis.opsForValue().get(user(7L)));
        assertTrue(redis.getExpire(user(7L)) > 120);
        begin();
        guard.cancelled(order("first"));
        assertEquals("C:first", redis.opsForValue().get(user(7L)));
        finish(false);
        assertEquals("C:first", redis.opsForValue().get(user(7L)));
        begin();
        guard.cancelled(order("first"));
        finish(true);
        assertNull(redis.opsForValue().get(user(7L)));
        assertNull(redis.opsForValue().get(stock()));
        begin();
        guard.reserve(slot, 7L, "second");
        finish(true);
        assertEquals("C:second", redis.opsForValue().get(user(7L)));
    }

    @Test
    void databaseRollbackReleasesOnlyThisOrderAndAllowsRetry() {
        begin();
        guard.reserve(slot, 7L, "failed");
        finish(false);
        assertNull(redis.opsForValue().get(stock()));
        assertNull(redis.opsForValue().get(user(7L)));
        begin();
        guard.reserve(slot, 7L, "retry");
        finish(true);
        begin();
        guard.cancelled(order("failed"));
        finish(true);
        assertEquals("C:retry", redis.opsForValue().get(user(7L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutAfterRedisExecutedStillCleansActualPreoccupationOnRollback() {
        StringRedisTemplate unstable = spy(redis);
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            assertEquals(0L, result);
                            throw new RedisConnectionFailureException(
                                    "reply lost after script executed");
                        })
                .when(unstable)
                .execute(
                        any(RedisScript.class),
                        anyList(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        BookingRedisGuard unreliable = new BookingRedisGuard(unstable, properties);
        begin();
        assertThrows(BookingException.class, () -> unreliable.reserve(slot, 7L, "timed-out"));
        assertEquals("P:timed-out", redis.opsForValue().get(user(7L)));
        finish(false);
        assertNull(redis.opsForValue().get(user(7L)));
        assertNull(redis.opsForValue().get(stock()));
    }

    @Test
    void pendingRequestAcrossDifferentSlotsForSameUserIsRejected() {
        begin();
        guard.reserve(slot, 7L, "pending");
        VenueSlot other =
                new VenueSlot()
                        .setId(101L)
                        .setCourtId(11L)
                        .setBookingDate(slot.getBookingDate())
                        .setCapacity(1)
                        .setBookedCount(0);
        assertThrows(BookingException.class, () -> guard.reserve(other, 7L, "another"));
        assertEquals("P:pending", redis.opsForValue().get(user(7L)));
        finish(false);
    }

    @Test
    void concurrentDifferentVenueRequestsForOneUserOnlyPreoccupyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch attempted = new CountDownLatch(8);
        AtomicInteger accepted = new AtomicInteger();
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int index = i;
                tasks.add(
                        pool.submit(
                                () -> {
                                    boolean reserved = false;
                                    begin();
                                    try {
                                        VenueSlot other =
                                                new VenueSlot()
                                                        .setId(200L + index)
                                                        .setCourtId(20L + index)
                                                        .setBookingDate(slot.getBookingDate())
                                                        .setCapacity(1)
                                                        .setBookedCount(0);
                                        try {
                                            guard.reserve(other, 7L, "parallel-" + index);
                                            reserved = true;
                                            accepted.incrementAndGet();
                                        } catch (BookingException expected) {
                                        }
                                        attempted.countDown();
                                        assertTrue(attempted.await(3, TimeUnit.SECONDS));
                                        finish(reserved);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(e);
                                    } finally {
                                        if (TransactionSynchronizationManager
                                                .isSynchronizationActive()) {
                                            finish(false);
                                        }
                                    }
                                }));
            }
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accepted.get());
        assertTrue(redis.opsForValue().get(user(7L)).startsWith("C:parallel-"));
    }

    @Test
    void lockedDatabaseSnapshotRepairsStaleStockAndStaleConfirmedMarker() {
        redis.opsForValue().set(stock(), "0");
        redis.opsForValue().set(user(7L), "C:cancelled-earlier");
        begin();
        guard.reserve(slot, 7L, "new");
        finish(true);
        assertEquals("0", redis.opsForValue().get(stock()));
        assertEquals("C:new", redis.opsForValue().get(user(7L)));
        slot.setBookedCount(1);
        begin();
        assertThrows(BookingException.class, () -> guard.reserve(slot, 8L, "full"));
        finish(false);
        assertNull(redis.opsForValue().get(user(8L)));
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
