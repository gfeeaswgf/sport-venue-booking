package com.hmdp;

import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;
import com.hmdp.mapper.VenueCourtMapper;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.mapper.VenueSlotMapper;
import com.hmdp.service.impl.VenueServiceImpl;
import com.hmdp.utils.BookingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingRollingSlotsTest {
    private VenueServiceImpl service;
    private VenueMapper venues;
    private VenueCourtMapper courts;
    private VenueSlotMapper slots;
    private BookingCache cache;
    private Venue venue;
    private VenueCourt court;
    private List<VenueSlot> stored;
    private final LocalDateTime morning = LocalDateTime.of(2026, 11, 14, 7, 0);

    @BeforeEach
    void setup() {
        service = new VenueServiceImpl();
        venues = mock(VenueMapper.class);
        courts = mock(VenueCourtMapper.class);
        slots = mock(VenueSlotMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", venues);
        ReflectionTestUtils.setField(service, "courtMapper", courts);
        ReflectionTestUtils.setField(service, "slotMapper", slots);
        cache = mock(BookingCache.class);
        ReflectionTestUtils.setField(service, "bookingCache", cache);
        venue = new Venue().setId(1001L).setEnabled(true);
        court =
                new VenueCourt()
                        .setId(1101L)
                        .setVenueId(1001L)
                        .setEnabled(true)
                        .setMode("COURT")
                        .setCapacity(1)
                        .setMaxPeople(6);
        stored = new ArrayList<>();
        when(venues.lockById(1001L)).thenReturn(venue);
        when(courts.selectList(any())).thenReturn(Collections.singletonList(court));
        when(slots.selectList(any())).thenAnswer(call -> new ArrayList<>(stored));
        when(slots.insert(any(VenueSlot.class)))
                .thenAnswer(
                        call -> {
                            stored.add(call.getArgument(0));
                            return 1;
                        });
    }

    @Test
    void generatesSevenDaysAndRepeatingDoesNotDuplicate() {
        assertEquals(91, service.ensureRollingSlots(1001L, morning));
        assertEquals(0, service.ensureRollingSlots(1001L, morning));
        assertEquals(91, stored.size());
        assertEquals(LocalDate.of(2026, 11, 14), stored.get(0).getBookingDate());
        assertEquals(LocalDate.of(2026, 11, 20), stored.get(90).getBookingDate());
        assertTrue(
                stored.stream()
                        .allMatch(
                                s ->
                                        s.getCapacity() == 1
                                                && s.getBookedCount() == 0
                                                && s.getEnabled()));
        verify(cache).slotsChanged(1101L, morning.toLocalDate());
        verify(cache).slotsChanged(1101L, morning.toLocalDate().plusDays(6));
    }

    @Test
    void followingDayAddsNewDayWithoutMovingHistoricalDates() {
        service.ensureRollingSlots(1001L, morning);
        VenueSlot original = stored.get(0);
        assertEquals(13, service.ensureRollingSlots(1001L, morning.plusDays(1)));
        assertEquals(104, stored.size());
        assertEquals(LocalDate.of(2026, 11, 14), original.getBookingDate());
        assertEquals(
                13,
                stored.stream()
                        .filter(s -> s.getBookingDate().equals(LocalDate.of(2026, 11, 21)))
                        .count());
    }

    @Test
    void preservesClosedSlotsAndBookedCapacity() {
        VenueSlot existing =
                new VenueSlot()
                        .setId(900L)
                        .setCourtId(1101L)
                        .setBookingDate(morning.toLocalDate())
                        .setStartTime(LocalTime.of(8, 0))
                        .setEndTime(LocalTime.of(9, 0))
                        .setEnabled(false)
                        .setBookedCount(1)
                        .setCapacity(1);
        stored.add(existing);
        assertEquals(90, service.ensureRollingSlots(1001L, morning));
        assertFalse(existing.getEnabled());
        assertEquals(1, existing.getBookedCount());
        assertEquals(900L, existing.getId());
        assertEquals(
                1,
                stored.stream()
                        .filter(
                                s ->
                                        s.getBookingDate().equals(morning.toLocalDate())
                                                && s.getStartTime().equals(LocalTime.of(8, 0)))
                        .count());
    }

    @Test
    void doesNotInsertOverlappingCustomTimes() {
        stored.add(
                new VenueSlot()
                        .setCourtId(1101L)
                        .setBookingDate(morning.toLocalDate())
                        .setStartTime(LocalTime.of(8, 30))
                        .setEndTime(LocalTime.of(10, 30))
                        .setEnabled(false)
                        .setBookedCount(0));
        assertEquals(88, service.ensureRollingSlots(1001L, morning));
        assertTrue(
                stored.stream()
                        .noneMatch(
                                s ->
                                        s.getBookingDate().equals(morning.toLocalDate())
                                                && LocalTime.of(9, 0).equals(s.getStartTime())));
    }

    @Test
    void closedVenuesAndCourtsAreSkipped() {
        venue.setEnabled(false);
        assertEquals(0, service.ensureRollingSlots(1001L, morning));
        venue.setEnabled(true);
        court.setEnabled(false);
        assertEquals(0, service.ensureRollingSlots(1001L, morning));
        assertTrue(stored.isEmpty());
    }

    @Test
    void swimmingUsesFourSessionsAndCurrentCourtCapacity() {
        court.setMode("SESSION").setCapacity(350).setMaxPeople(1);
        assertEquals(28, service.ensureRollingSlots(1001L, morning));
        assertEquals(LocalTime.NOON, stored.get(0).getStartTime());
        assertEquals(LocalTime.of(14, 10), stored.get(0).getEndTime());
        assertTrue(stored.stream().allMatch(s -> s.getCapacity() == 350));
    }

    @Test
    void neverBackfillsAlreadyStartedSessionsToday() {
        assertEquals(78, service.ensureRollingSlots(1001L, morning.withHour(22)));
        assertTrue(
                stored.stream().allMatch(s -> s.getBookingDate().isAfter(morning.toLocalDate())));
    }
}
