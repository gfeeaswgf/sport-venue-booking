package com.hmdp;

import com.hmdp.dto.BookingProfileDTO;
import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BookingOrder;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;
import com.hmdp.mapper.BookingOrderMapper;
import com.hmdp.mapper.VenueCourtMapper;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.mapper.VenueSlotMapper;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.BookingOrderServiceImpl;
import com.hmdp.utils.BookingCache;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRedisGuard;
import com.hmdp.utils.BookingRules;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingOrderServiceTest {
    BookingOrderServiceImpl service;
    BookingOrderMapper orders;
    VenueSlotMapper slots;
    VenueMapper venues;
    VenueCourtMapper courts;
    IUserService users;
    VenueSlot slot;
    BookingRedisGuard guard;
    BookingCache cache;

    @BeforeEach
    void setup() {
        service = new BookingOrderServiceImpl();
        orders = mock(BookingOrderMapper.class);
        slots = mock(VenueSlotMapper.class);
        venues = mock(VenueMapper.class);
        courts = mock(VenueCourtMapper.class);
        users = mock(IUserService.class);
        ReflectionTestUtils.setField(service, "baseMapper", orders);
        ReflectionTestUtils.setField(service, "slotMapper", slots);
        ReflectionTestUtils.setField(service, "venueMapper", venues);
        ReflectionTestUtils.setField(service, "courtMapper", courts);
        ReflectionTestUtils.setField(service, "userService", users);
        guard = mock(BookingRedisGuard.class);
        cache = mock(BookingCache.class);
        ReflectionTestUtils.setField(service, "bookingRedisGuard", guard);
        ReflectionTestUtils.setField(service, "bookingCache", cache);
        UserDTO login = new UserDTO();
        login.setId(7L);
        UserHolder.saveUser(login);
        Venue venue = new Venue();
        venue.setId(1L);
        venue.setName("羽毛球馆");
        venue.setEnabled(true);
        venue.setCancelAdvanceMinutes(60);
        VenueCourt court = new VenueCourt();
        court.setId(10L);
        court.setVenueId(1L);
        court.setName("1号场");
        court.setMode("COURT");
        court.setMaxPeople(6);
        court.setEnabled(true);
        slot = new VenueSlot();
        slot.setId(100L);
        slot.setCourtId(10L);
        slot.setBookingDate(BookingRules.now().toLocalDate().plusDays(1));
        slot.setStartTime(LocalTime.of(14, 0));
        slot.setEndTime(LocalTime.of(15, 0));
        slot.setCapacity(1);
        slot.setBookedCount(0);
        slot.setEnabled(true);
        when(slots.selectById(100L)).thenReturn(slot);
        when(slots.lockById(100L)).thenReturn(slot);
        when(courts.selectById(10L)).thenReturn(court);
        when(courts.lockById(10L)).thenReturn(court);
        when(venues.lockById(1L)).thenReturn(venue);
        BookingProfileDTO profile = new BookingProfileDTO();
        profile.setId(7L);
        profile.setRealName("本人姓名");
        profile.setStudentNo("2026007");
        profile.setPhone("13800000007");
        when(users.queryProfile()).thenReturn(Result.ok(profile));
        when(slots.acquire(100L)).thenReturn(1);
        when(orders.insert(any())).thenReturn(1);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    BookingRequestDTO request() {
        BookingRequestDTO r = new BookingRequestDTO();
        r.setSlotId(100L);
        r.setAgreement(true);
        return r;
    }

    @Test
    void orderSnapshotComesFromAuthenticatedProfile() {
        Result result = service.createOrder(request());
        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof String);
        ArgumentCaptor<BookingOrder> saved = ArgumentCaptor.forClass(BookingOrder.class);
        verify(orders).insert(saved.capture());
        BookingOrder order = saved.getValue();
        assertEquals(7L, order.getUserId());
        assertEquals("本人姓名", order.getRealName());
        assertEquals("2026007", order.getStudentNo());
        assertEquals("13800000007", order.getPhone());
        assertEquals(slot.getBookingDate(), order.getActiveDate());
        assertEquals("RESERVED", order.getStatus());
        assertNotNull(order.getVerifyCode());
        verify(guard).reserve(slot, 7L, order.getId());
        verify(cache).slotsChanged(slot.getCourtId(), slot.getBookingDate());
    }

    @Test
    void noOrderIsInsertedWhenCapacityRunsOut() {
        when(slots.acquire(100L)).thenReturn(0);
        assertThrows(BookingException.class, () -> service.createOrder(request()));
        verify(orders, never()).insert(any());
    }

    @Test
    void missingProfileAndAgreementPreventBooking() {
        BookingRequestDTO request = request();
        request.setAgreement(false);
        assertThrows(BookingException.class, () -> service.createOrder(request));
        request.setAgreement(true);
        when(users.queryProfile()).thenReturn(Result.ok(new BookingProfileDTO()));
        assertThrows(BookingException.class, () -> service.createOrder(request));
        verify(slots, never()).acquire(anyLong());
    }

    @Test
    void cannotReadAnotherUsersOrder() {
        BookingOrder order = new BookingOrder();
        order.setId("other");
        order.setUserId(99L);
        when(orders.selectById("other")).thenReturn(order);
        assertThrows(BookingException.class, () -> service.orderDetail("other"));
    }

    @Test
    void cannotCancelAnotherUsersOrder() {
        BookingOrder order = new BookingOrder();
        order.setId("other");
        order.setUserId(99L);
        when(orders.selectById("other")).thenReturn(order);
        assertThrows(BookingException.class, () -> service.cancelOrder("other"));
        verify(slots, never()).release(anyLong());
    }

    @Test
    void cancellationReturnsCapacityOnlyOnce() {
        BookingOrder order = new BookingOrder();
        order.setId("mine");
        order.setUserId(7L);
        order.setVenueId(1L);
        order.setSlotId(100L);
        order.setBookingDate(slot.getBookingDate());
        order.setStartTime(slot.getStartTime());
        order.setEndTime(slot.getEndTime());
        order.setStatus("RESERVED");
        order.setCancelAdvanceMinutes(60);
        when(orders.selectById("mine")).thenReturn(order);
        when(orders.lockById("mine")).thenReturn(order);
        when(orders.cancel("mine", 7L)).thenReturn(1, 0);
        when(slots.release(100L)).thenReturn(1);
        assertTrue(service.cancelOrder("mine").getSuccess());
        assertThrows(BookingException.class, () -> service.cancelOrder("mine"));
        verify(slots, times(1)).release(100L);
        verify(guard).cancelled(order);
    }

    @Test
    void rateLimitStopsRequestBeforeProfileAndDatabaseReads() {
        doThrow(new BookingException("提交过于频繁，请稍后重试")).when(guard).checkRate(7L);
        assertThrows(BookingException.class, () -> service.createOrder(request()));
        verify(users, never()).queryProfile();
        verify(slots, never()).selectById(anyLong());
    }

    @Test
    void redisPreoccupationFailureDoesNotDecreaseDatabaseCapacityOrInsertOrder() {
        doThrow(new BookingException("预约请求正在处理中，请稍后查看预约记录"))
                .when(guard)
                .reserve(any(), eq(7L), anyString());
        assertThrows(BookingException.class, () -> service.createOrder(request()));
        verify(slots, never()).acquire(anyLong());
        verify(orders, never()).insert(any());
    }
}
