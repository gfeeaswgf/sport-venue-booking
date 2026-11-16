package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hmdp.config.BookingRedisProperties;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;
import com.hmdp.mapper.VenueCourtMapper;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.mapper.VenueSlotMapper;
import com.hmdp.service.impl.VenueServiceImpl;
import com.hmdp.utils.BookingCache;
import com.hmdp.utils.BookingRules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class BookingRedisCacheTest {
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    Map<String, String> data;
    Map<String, Long> expiry;
    BookingCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        data = new ConcurrentHashMap<>();
        expiry = new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(c -> data.get(c.getArgument(0)));
        doAnswer(
                        c -> {
                            data.put(c.getArgument(0), c.getArgument(1));
                            expiry.put(c.getArgument(0), c.getArgument(2));
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        doAnswer(
                        c -> {
                            data.put(c.getArgument(0), c.getArgument(1));
                            expiry.put(c.getArgument(0), -1L);
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString());
        when(redis.delete(anyString())).thenAnswer(c -> data.remove(c.getArgument(0)) != null);
        RedisConnection connection =
                mock(
                        RedisConnection.class,
                        c -> {
                            if (!"del".equals(c.getMethod().getName())) {
                                return org.mockito.Answers.RETURNS_DEFAULTS.answer(c);
                            }
                            long removed = 0;
                            for (Object arg : c.getArguments()) {
                                if (arg instanceof byte[]) {
                                    if (data.remove(
                                                    new String(
                                                            (byte[]) arg, StandardCharsets.UTF_8))
                                            != null) {
                                        removed++;
                                    }
                                } else {
                                    for (byte[] key : (byte[][]) arg) {
                                        if (data.remove(new String(key, StandardCharsets.UTF_8))
                                                != null) {
                                            removed++;
                                        }
                                    }
                                }
                            }
                            return removed;
                        });
        when(connection.scan(any(ScanOptions.class)))
                .thenAnswer(
                        c -> {
                            Iterator<String> keys =
                                    new ArrayList<>(data.keySet())
                                            .stream()
                                                    .filter(k -> k.startsWith("cache:booking:"))
                                                    .iterator();
                            Cursor<byte[]> cursor = mock(Cursor.class);
                            when(cursor.hasNext()).thenAnswer(x -> keys.hasNext());
                            when(cursor.next())
                                    .thenAnswer(x -> keys.next().getBytes(StandardCharsets.UTF_8));
                            return cursor;
                        });
        when(redis.execute(any(RedisCallback.class)))
                .thenAnswer(c -> ((RedisCallback<?>) c.getArgument(0)).doInRedis(connection));
        cache = new BookingCache(redis, new BookingRedisProperties());
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void basicInformationIsPermanentButMissingDataAndSlotsExpire() {
        cache.get("venue:1", Venue.class, () -> new Venue().setName("羽毛球馆"));
        cache.getList("venues:all", Venue.class, () -> Collections.singletonList(new Venue()));
        cache.getList(
                "courts:1", VenueCourt.class, () -> Collections.singletonList(new VenueCourt()));
        assertEquals(-1L, expiry.get("cache:booking:venue:1"));
        assertEquals(-1L, expiry.get("cache:booking:courts:1"));
        assertTrue(
                expiry.entrySet().stream()
                        .filter(e -> e.getKey().startsWith("cache:booking:venues:"))
                        .allMatch(e -> e.getValue() == -1L));
        cache.get("venue:missing", Venue.class, () -> null);
        cache.getList("courts:empty", VenueCourt.class, Collections::emptyList);
        LocalDate date = LocalDate.of(2026, 11, 15);
        cache.getSlots(2L, date, () -> Collections.singletonList(new VenueSlot()));
        assertEquals(30L, expiry.get("cache:booking:venue:missing"));
        assertEquals(10L, expiry.get("cache:booking:courts:empty"));
        assertEquals(20L, expiry.get("cache:booking:slots:2:2026-11-15"));
    }

    @Test
    void cachedVenueKeepsFieldsAndDoesNotReloadDatabase() {
        AtomicInteger loads = new AtomicInteger();
        Venue venue =
                new Venue()
                        .setId(1L)
                        .setName("羽毛球馆")
                        .setEnabled(true)
                        .setCreateTime(LocalDateTime.of(2026, 11, 14, 8, 0));
        cache.get(
                "venue:1",
                Venue.class,
                () -> {
                    loads.incrementAndGet();
                    return venue;
                });
        Venue hit =
                cache.get(
                        "venue:1",
                        Venue.class,
                        () -> {
                            loads.incrementAndGet();
                            return null;
                        });
        assertEquals(1, loads.get());
        assertEquals("羽毛球馆", hit.getName());
        assertEquals(venue.getCreateTime(), hit.getCreateTime());
        assertTrue(hit.getEnabled());
        assertTrue(data.containsKey("cache:booking:venue:1"));
    }

    @Test
    void cachedSlotsKeepActualDateAndTimeAndInvalidationLoadsNewCapacity() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        VenueSlot slot =
                new VenueSlot()
                        .setId(11L)
                        .setCourtId(2L)
                        .setBookingDate(date)
                        .setStartTime(LocalTime.of(14, 30))
                        .setEndTime(LocalTime.of(16, 10))
                        .setCapacity(350)
                        .setBookedCount(1);
        cache.getSlots(2L, date, () -> Collections.singletonList(slot));
        List<VenueSlot> hit = cache.getSlots(2L, date, Collections::emptyList);
        assertEquals(date, hit.get(0).getBookingDate());
        assertEquals(slot.getStartTime(), hit.get(0).getStartTime());
        cache.slotsChanged(2L, date);
        assertTrue(cache.getSlots(2L, date, Collections::emptyList).isEmpty());
    }

    @Test
    void catalogChangesAreVisibleOnlyAfterCommitAndRollbackPreservesCache() {
        Venue old = new Venue().setName("原场馆");
        cache.get("venue:1", Venue.class, () -> old);
        TransactionSynchronizationManager.initSynchronization();
        cache.catalogChanged();
        assertEquals(
                "原场馆",
                cache.get("venue:1", Venue.class, () -> new Venue().setName("未提交")).getName());
        for (TransactionSynchronization callback :
                TransactionSynchronizationManager.getSynchronizations()) {
            callback.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();
        assertEquals(
                "新场馆",
                cache.get("venue:1", Venue.class, () -> new Venue().setName("新场馆")).getName());
        TransactionSynchronizationManager.initSynchronization();
        cache.catalogChanged();
        for (TransactionSynchronization callback :
                TransactionSynchronizationManager.getSynchronizations()) {
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clearSynchronization();
        assertEquals("新场馆", cache.get("venue:1", Venue.class, () -> old).getName());
    }

    @Test
    void catalogInvalidationDeletesQueryKeysButPreservesLoginAndReservationState() {
        cache.get("venue:1", Venue.class, () -> new Venue().setName("旧场馆"));
        cache.getList("venues:BADMINTON", Venue.class, Collections::emptyList);
        cache.getList("courts:1", VenueCourt.class, Collections::emptyList);
        cache.getSlots(2L, LocalDate.of(2026, 11, 15), Collections::emptyList);
        data.put("login:token:test", "login");
        data.put("booking:stock:11", "1");
        cache.catalogChanged();
        assertEquals(
                new HashSet<>(Arrays.asList("login:token:test", "booking:stock:11")),
                data.keySet());
        assertEquals(
                "新场馆",
                cache.get("venue:1", Venue.class, () -> new Venue().setName("新场馆")).getName());
    }

    @Test
    void redisFailureFallsBackButDatabaseFailureDoesNotRunLoaderTwice() {
        when(values.get(anyString()))
                .thenThrow(
                        new org.springframework.data.redis.RedisConnectionFailureException(
                                "offline"));
        assertEquals(
                "数据库",
                cache.get("venue:1", Venue.class, () -> new Venue().setName("数据库")).getName());
        AtomicInteger loads = new AtomicInteger();
        assertThrows(
                IllegalStateException.class,
                () ->
                        cache.get(
                                "venue:2",
                                Venue.class,
                                () -> {
                                    loads.incrementAndGet();
                                    throw new IllegalStateException("database offline");
                                }));
        assertEquals(1, loads.get());
    }

    @Test
    void emptyResultsAndDifferentFiltersAreCachedSeparately() {
        AtomicInteger loads = new AtomicInteger();
        assertTrue(
                cache.getList(
                                "venues:BADMINTON",
                                Venue.class,
                                () -> {
                                    loads.incrementAndGet();
                                    return Collections.emptyList();
                                })
                        .isEmpty());
        assertTrue(
                cache.getList(
                                "venues:BADMINTON",
                                Venue.class,
                                () -> {
                                    loads.incrementAndGet();
                                    return Collections.singletonList(new Venue());
                                })
                        .isEmpty());
        assertEquals(1, loads.get());
        assertEquals(
                "游泳馆",
                cache.getList(
                                "venues:SWIMMING",
                                Venue.class,
                                () -> Collections.singletonList(new Venue().setName("游泳馆")))
                        .get(0)
                        .getName());
    }

    @Test
    void missingVenueIsNegativelyCached() {
        AtomicInteger loads = new AtomicInteger();
        assertNull(
                cache.get(
                        "venue:missing",
                        Venue.class,
                        () -> {
                            loads.incrementAndGet();
                            return null;
                        }));
        assertNull(
                cache.get(
                        "venue:missing",
                        Venue.class,
                        () -> {
                            loads.incrementAndGet();
                            return null;
                        }));
        assertEquals(1, loads.get());
    }

    @Test
    void publicVenueQueriesUseCacheAndSlotsRecomputeTimeDependentAvailability() {
        VenueServiceImpl service = new VenueServiceImpl();
        VenueMapper venues = mock(VenueMapper.class);
        VenueCourtMapper courts = mock(VenueCourtMapper.class);
        VenueSlotMapper slots = mock(VenueSlotMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", venues);
        ReflectionTestUtils.setField(service, "courtMapper", courts);
        ReflectionTestUtils.setField(service, "slotMapper", slots);
        ReflectionTestUtils.setField(service, "bookingCache", cache);
        com.hmdp.utils.BookingAdmissionRedis admission =
                mock(com.hmdp.utils.BookingAdmissionRedis.class);
        when(admission.remaining(any(), anyInt())).thenAnswer(call -> call.getArgument(1));
        ReflectionTestUtils.setField(service, "admission", admission);
        when(venues.selectList(any()))
                .thenReturn(Collections.singletonList(new Venue().setId(1L).setName("羽毛球馆")));
        service.listVenues(null, "BADMINTON", false);
        service.listVenues(null, "BADMINTON", false);
        verify(venues, times(1)).selectList(any());
        when(courts.selectById(2L))
                .thenReturn(new VenueCourt().setId(2L).setVenueId(1L).setEnabled(true));
        when(venues.selectById(1L)).thenReturn(new Venue().setId(1L).setEnabled(true));
        LocalDate date = BookingRules.now().toLocalDate();
        VenueSlot slot =
                new VenueSlot()
                        .setId(11L)
                        .setCourtId(2L)
                        .setBookingDate(date)
                        .setEnabled(true)
                        .setStartTime(LocalTime.MIDNIGHT)
                        .setEndTime(LocalTime.of(1, 0))
                        .setCapacity(1)
                        .setBookedCount(0)
                        .setAvailable(true);
        cache.getSlots(2L, date, () -> Collections.singletonList(slot));
        @SuppressWarnings("unchecked")
        List<VenueSlot> result = (List<VenueSlot>) service.listSlots(2L, date, false).getData();
        assertFalse(result.get(0).getAvailable());
        assertEquals("已开始", result.get(0).getStatusText());
        verify(slots, never()).selectList(any());
    }
}
