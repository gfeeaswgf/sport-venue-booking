package com.hmdp;

import com.hmdp.config.BookingRedisProperties;
import com.hmdp.entity.BookingOrder;
import com.hmdp.entity.VenueSlot;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRedisGuard;
import com.hmdp.utils.BookingRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingRedisGuardTest {
    StringRedisTemplate redis;
    BookingRedisProperties properties;
    BookingRedisGuard guard;
    VenueSlot slot;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        properties = new BookingRedisProperties();
        guard = new BookingRedisGuard(redis, properties);
        slot =
                new VenueSlot()
                        .setId(1L)
                        .setBookingDate(BookingRules.now().toLocalDate().plusDays(1))
                        .setCapacity(1)
                        .setBookedCount(0);
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void disabledRedisPreservesSqlOnlyModeWithoutContactingRedis() {
        properties.setEnabled(false);
        guard.checkRate(7L);
        guard.reserve(slot, 7L, "order");
        guard.cancelled(new BookingOrder());
        verifyNoInteractions(redis);
    }

    @Test
    void reservationRequiresTransactionToEnsureRollbackCompensation() {
        assertThrows(IllegalStateException.class, () -> guard.reserve(slot, 7L, "order"));
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableRedisIsReportedAsBusinessErrorAndStillRegistersRollback() {
        TransactionSynchronizationManager.initSynchronization();
        when(redis.execute(
                        any(RedisScript.class),
                        anyList(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenThrow(
                        new RedisConnectionFailureException("execution may have reached server"));
        BookingException error =
                assertThrows(BookingException.class, () -> guard.reserve(slot, 7L, "order"));
        assertTrue(error.getMessage().contains("暂不可用"));
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        assertDoesNotThrow(
                () ->
                        TransactionSynchronizationUtils.triggerAfterCompletion(
                                TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(redis)
                .execute(
                        any(RedisScript.class),
                        anyList(),
                        eq("rollback"),
                        eq("order"),
                        anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisConfirmationFailureCannotTurnCommittedDatabaseOrderIntoFailure() {
        TransactionSynchronizationManager.initSynchronization();
        when(redis.execute(
                        any(RedisScript.class),
                        anyList(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(0L);
        when(redis.execute(
                        any(RedisScript.class), anyList(), eq("confirm"), eq("order"), anyString()))
                .thenThrow(new RedisConnectionFailureException("offline after database commit"));
        guard.reserve(slot, 7L, "order");
        assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
        assertDoesNotThrow(
                () ->
                        TransactionSynchronizationUtils.triggerAfterCompletion(
                                TransactionSynchronization.STATUS_COMMITTED));
    }
}
