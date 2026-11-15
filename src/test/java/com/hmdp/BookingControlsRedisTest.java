package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.dto.IdempotencyRecord;
import com.hmdp.dto.OperationLogDTO;
import com.hmdp.service.impl.OperationLogServiceImpl;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRequestRedis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 只使用显式开启的独立 Redis，不读取 application.yaml 的业务配置。 */
@EnabledIfSystemProperty(named = "booking.redis-integration", matches = "true")
class BookingControlsRedisTest {
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    BookingControlsProperties controls;
    BookingRedisProperties cache;
    BookingRequestRedis guard;
    String prefix;
    List<String> keys;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration("127.0.0.1", 16389);
        factory =
                new LettuceConnectionFactory(
                        configuration,
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
        prefix = "test:aop-controls:" + UUID.randomUUID() + ":";
        controls = new BookingControlsProperties();
        cache = new BookingRedisProperties();
        cache.setNamespace(prefix);
        guard = new BookingRequestRedis(redis, controls, cache);
        keys = new ArrayList<>();
    }

    @AfterEach
    void cleanup() {
        // 只删除本测试记录的随机前缀 Key，绝不 FLUSHDB 或扫描业务 Key。
        for (int i = 1; i <= 2; i++) {
            keys.add(prefix + "booking:rate:CREATE:user:7:" + i);
            keys.add(prefix + "booking:rate:CREATE:user:8:" + i);
            keys.add(prefix + "booking:rate:CREATE:api:" + i);
            keys.add(prefix + "booking:rate:CREATE:slot:100:" + i);
        }
        redis.delete(keys);
    }

    @Test
    void thirdRequestIsRejectedWhileOtherUsersHaveTheirOwnAllowance() {
        guard.checkRate(7L, 100L, "one");
        guard.checkRate(7L, 100L, "two");
        assertThrows(BookingException.class, () -> guard.checkRate(7L, 100L, "three"));
        assertDoesNotThrow(() -> guard.checkRate(8L, 100L, "four"));
        assertTrue(
                redis.getExpire(prefix + "booking:rate:CREATE:user:7:1", TimeUnit.MILLISECONDS)
                        > 0);
    }

    @Test
    void concurrentRequestsOnlyAdmitTwo() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                final String requestId = "request-" + i;
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        guard.checkRate(7L, 100L, requestId);
                                        return true;
                                    } catch (BookingException rejected) {
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertEquals(2, admitted);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eachPermitExpiresIndependentlyInsteadOfResettingAtAWindowBoundary() throws Exception {
        controls.setWindowMillis(400);
        guard.checkRate(7L, 100L, "first");
        Thread.sleep(220);
        guard.checkRate(7L, 100L, "second");
        assertThrows(BookingException.class, () -> guard.checkRate(7L, 100L, "third"));
        Thread.sleep(220);
        guard.checkRate(7L, 100L, "third");
        assertThrows(BookingException.class, () -> guard.checkRate(7L, 100L, "fourth"));
    }

    @Test
    void concurrentDuplicatesOnlyAcquireOneProcessingRecord() throws Exception {
        key(7L, "duplicate");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                final String owner = "owner-" + i;
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return guard.begin(7L, "duplicate", "same", owner).getState();
                                }));
            }
            start.countDown();
            int acquired = 0;
            for (Future<String> result : results) {
                if ("ACQUIRED".equals(result.get(5, TimeUnit.SECONDS))) {
                    acquired++;
                }
            }
            assertEquals(1, acquired);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void successfulResultIsReplayedForThreeDaysWithoutRenewal() {
        String key = key(7L, "request");
        assertEquals("ACQUIRED", guard.begin(7L, "request", "fingerprint", "owner").getState());
        assertTrue(redis.getExpire(key) <= 120);
        assertEquals("PROCESSING", guard.begin(7L, "request", "fingerprint", "other").getState());
        assertTrue(guard.complete(7L, "request", "fingerprint", "owner", "order-1"));
        assertTrue(redis.getExpire(key) >= 259198);
        redis.expire(key, 60, TimeUnit.SECONDS);
        IdempotencyRecord replay = guard.begin(7L, "request", "fingerprint", "retry");
        assertEquals("SUCCESS", replay.getState());
        assertEquals("order-1", replay.getOrderId());
        assertTrue(redis.getExpire(key) <= 60);
    }

    @Test
    void contentConflictsAndStaleOwnersCannotChangeAnotherExecution() {
        String key = key(7L, "request");
        guard.begin(7L, "request", "original", "old");
        assertEquals("CONFLICT", guard.begin(7L, "request", "changed", "other").getState());
        assertFalse(guard.release(7L, "request", "original", "wrong"));
        assertTrue(guard.release(7L, "request", "original", "old"));
        guard.begin(7L, "request", "original", "new");
        assertFalse(guard.release(7L, "request", "original", "old"));
        assertFalse(guard.complete(7L, "request", "original", "old", "stale-order"));
        assertEquals("new", redis.opsForHash().get(key, "owner"));
        assertEquals("ACQUIRED", guard.begin(8L, "request", "original", "independent").getState());
        key(8L, "request");
    }

    @Test
    void optionalGlobalAndSlotLimitsProtectDifferentUsers() {
        controls.setApiLimit(1);
        controls.setSlotLimit(1);
        guard.checkRate(7L, 100L, "one");
        assertThrows(BookingException.class, () -> guard.checkRate(8L, 100L, "two"));
        controls.setApiLimit(0);
        controls.setSlotLimit(1);
        assertThrows(BookingException.class, () -> guard.checkRate(8L, 100L, "three"));
    }

    @Test
    void auditEntriesExpireIndependentlyAfterThreeDays() {
        OperationLogServiceImpl logs = new OperationLogServiceImpl(redis, controls, cache);
        OperationLogDTO first = new OperationLogDTO();
        first.setRequestId("audit-one");
        first.setOperation("CREATE_BOOKING");
        logs.saveLog(first);
        String firstKey = prefix + "booking:audit:audit-one";
        String secondKey = prefix + "booking:audit:audit-two";
        keys.add(firstKey);
        keys.add(secondKey);
        assertTrue(redis.getExpire(firstKey) >= 259198);
        redis.expire(firstKey, 60, TimeUnit.SECONDS);
        OperationLogDTO second = new OperationLogDTO();
        second.setRequestId("audit-two");
        logs.saveLog(second);
        assertTrue(redis.getExpire(firstKey) <= 60);
        assertTrue(redis.getExpire(secondKey) >= 259198);
        assertTrue(redis.opsForValue().get(firstKey).contains("CREATE_BOOKING"));
    }

    private String key(Long userId, String request) {
        String key = prefix + "booking:idempotent:CREATE:" + userId + ":" + request;
        keys.add(key);
        return key;
    }
}
