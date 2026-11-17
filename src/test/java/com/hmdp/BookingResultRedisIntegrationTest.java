package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.utils.BookingResultRedis;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@EnabledIfSystemProperty(named = "booking.redis-integration", matches = "true")
class BookingResultRedisIntegrationTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

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
    void auditReplayAtWholeMinuteDoesNotConflictOrExtendLifetime() {
        Fixture fixture = new Fixture();
        fixture.task.setCompleteTime(BookingRules.now().truncatedTo(ChronoUnit.MINUTES));
        try {
            assertTrue(fixture.effects.writeAudit(fixture.task));
            redis.expire(fixture.auditKey(), 60, TimeUnit.SECONDS);
            assertTrue(fixture.effects.writeAudit(fixture.task));
            assertTrue(redis.getExpire(fixture.auditKey()) <= 60);
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void legacyNanosecondsAndJsonOrderAreCompatibleButDifferentOutcomeIsNot() {
        Fixture fixture = new Fixture();
        fixture.task.setCompleteTime(BookingRules.now().truncatedTo(ChronoUnit.SECONDS));
        try {
            assertTrue(fixture.effects.writeAudit(fixture.task));
            JSONObject legacy = JSONUtil.parseObj(redis.opsForValue().get(fixture.auditKey()));
            legacy.set(
                    "createTime", fixture.task.getCompleteTime().minusNanos(100000000).toString());
            redis.opsForValue().set(fixture.auditKey(), legacy.toString(), 120, TimeUnit.SECONDS);
            assertTrue(fixture.effects.writeAudit(fixture.task));
            legacy.set("outcome", "REJECTED");
            redis.opsForValue().set(fixture.auditKey(), legacy.toString(), 120, TimeUnit.SECONDS);
            assertThrows(
                    IllegalStateException.class, () -> fixture.effects.writeAudit(fixture.task));
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void statisticsReplayAndOlderSnapshotNeverDoubleCountOrDecrease() {
        Fixture fixture = new Fixture();
        try {
            assertTrue(fixture.effects.writeStatistics(fixture.task, 42));
            long expiry = redis.getExpire(fixture.statsKey(), TimeUnit.MILLISECONDS);
            assertTrue(fixture.effects.writeStatistics(fixture.task, 42));
            assertTrue(fixture.effects.writeStatistics(fixture.task, 41));
            assertEquals("42", redis.opsForHash().get(fixture.statsKey(), "1"));
            assertTrue(redis.getExpire(fixture.statsKey(), TimeUnit.MILLISECONDS) <= expiry);
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void expiredEffectsAreNotRecreated() {
        Fixture fixture = new Fixture();
        fixture.task.setCompleteTime(BookingRules.now().minusDays(4));
        fixture.task.setBookingDate(BookingRules.now().toLocalDate().minusDays(4));
        try {
            assertFalse(fixture.effects.writeAudit(fixture.task));
            assertFalse(fixture.effects.writeStatistics(fixture.task, 99));
            assertFalse(redis.hasKey(fixture.auditKey()));
            assertFalse(redis.hasKey(fixture.statsKey()));
        } finally {
            fixture.cleanup();
        }
    }

    private static class Fixture {

        private final String prefix = "test:booking-result:" + UUID.randomUUID() + ":";
        private final BookingRequestTask task = new BookingRequestTask();
        private final BookingResultRedis effects;

        private Fixture() {
            BookingRedisProperties properties = new BookingRedisProperties();
            properties.setNamespace(prefix);
            effects = new BookingResultRedis(redis, properties, new BookingControlsProperties());
            task.setId(UUID.randomUUID().toString().replace("-", ""));
            task.setUserId(7L);
            task.setVenueId(1L);
            task.setStatus("SUCCEEDED");
            task.setCompleteTime(BookingRules.now());
            task.setBookingDate(BookingRules.now().toLocalDate().plusDays(1));
        }

        private String auditKey() {
            return prefix + "booking:audit:" + task.getId();
        }

        private String statsKey() {
            return prefix + "booking:stats:created:" + task.getBookingDate();
        }

        private void cleanup() {
            redis.delete(auditKey());
            redis.delete(statsKey());
        }
    }
}
