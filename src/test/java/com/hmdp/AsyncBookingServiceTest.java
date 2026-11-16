package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.dto.BookingProfileDTO;
import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.BookingSubmissionDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;
import com.hmdp.mapper.BookingOrderMapper;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.mapper.VenueCourtMapper;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.mapper.VenueSlotMapper;
import com.hmdp.service.IBookingRequestPersistenceService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.AsyncBookingServiceImpl;
import com.hmdp.utils.BookingAdmissionRedis;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingOperationContext;
import com.hmdp.utils.BookingRules;
import com.hmdp.utils.UserHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;

import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

class AsyncBookingServiceTest {
    AsyncBookingServiceImpl service;
    BookingRequestMapper requests;
    BookingOrderMapper orders;
    BookingAdmissionRedis admission;
    IBookingRequestPersistenceService persistence;
    IUserService users;
    BookingRequestDTO input;

    @BeforeEach
    void setup() {
        service = new AsyncBookingServiceImpl();
        requests = mock(BookingRequestMapper.class);
        orders = mock(BookingOrderMapper.class);
        admission = mock(BookingAdmissionRedis.class);
        persistence = mock(IBookingRequestPersistenceService.class);
        users = mock(IUserService.class);
        VenueSlotMapper slots = mock(VenueSlotMapper.class);
        VenueCourtMapper courts = mock(VenueCourtMapper.class);
        VenueMapper venues = mock(VenueMapper.class);
        ReflectionTestUtils.setField(service, "requests", requests);
        ReflectionTestUtils.setField(service, "orders", orders);
        ReflectionTestUtils.setField(service, "admission", admission);
        ReflectionTestUtils.setField(service, "persistence", persistence);
        ReflectionTestUtils.setField(service, "users", users);
        ReflectionTestUtils.setField(service, "slots", slots);
        ReflectionTestUtils.setField(service, "courts", courts);
        ReflectionTestUtils.setField(service, "venues", venues);
        UserDTO login = new UserDTO();
        login.setId(7L);
        UserHolder.saveUser(login);
        BookingOperationContext.open();
        BookingProfileDTO profile = new BookingProfileDTO();
        profile.setId(7L);
        profile.setRealName("已保存姓名");
        profile.setStudentNo("2026007");
        profile.setPhone("13800000007");
        when(users.queryProfile()).thenReturn(Result.ok(profile));
        VenueSlot slot =
                new VenueSlot()
                        .setId(100L)
                        .setCourtId(10L)
                        .setBookingDate(BookingRules.now().toLocalDate().plusDays(1))
                        .setStartTime(LocalTime.NOON)
                        .setEndTime(LocalTime.of(13, 0))
                        .setEnabled(true);
        VenueCourt court =
                new VenueCourt()
                        .setId(10L)
                        .setVenueId(1L)
                        .setMode("COURT")
                        .setMaxPeople(6)
                        .setEnabled(true);
        when(slots.selectById(100L)).thenReturn(slot);
        when(courts.selectById(10L)).thenReturn(court);
        when(venues.selectById(1L)).thenReturn(new Venue().setId(1L).setEnabled(true));
        when(orders.selectCount(any())).thenReturn(0);
        when(requests.remainingForAdmission(100L)).thenReturn(1);
        input = new BookingRequestDTO();
        input.setSlotId(100L);
        input.setAgreement(true);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
        BookingOperationContext.clear();
    }

    @Test
    void acceptancePersistsFrozenProfileBeforeReturningProcessing() {
        org.mockito.ArgumentCaptor<BookingRequestTask> stored =
                org.mockito.ArgumentCaptor.forClass(BookingRequestTask.class);
        BookingSubmissionDTO result = service.accept(input, "key", "hash");
        assertEquals("PROCESSING", result.getState());
        assertNull(result.getOrderId());
        verify(persistence).save(stored.capture());
        assertEquals("已保存姓名", stored.getValue().getRealName());
        assertEquals(7L, stored.getValue().getUserId());
        assertEquals(result.getRequestId(), stored.getValue().getId());
        assertEquals("ACCEPTED", stored.getValue().getStatus());
        verify(admission).reserve(stored.getValue(), 1);
        verify(orders, never()).insert(any());
    }

    @Test
    void persistentReplaySurvivesMissingRedisAndDoesNotReserveAgain() {
        BookingRequestTask existing = task();
        when(requests.byKey(7L, "key")).thenReturn(existing);
        assertEquals(existing.getId(), service.accept(input, "key", "hash").getRequestId());
        verifyNoInteractions(users, persistence, admission);
    }

    @Test
    void sameKeyWithChangedContentIsRejected() {
        when(requests.byKey(7L, "key")).thenReturn(task());
        assertThrows(BookingException.class, () -> service.accept(input, "key", "changed"));
        verifyNoInteractions(persistence, admission);
    }

    @Test
    void existingDailyOrderRejectsBeforePreoccupation() {
        when(orders.selectCount(any())).thenReturn(1);
        assertThrows(BookingException.class, () -> service.accept(input, "key", "hash"));
        verifyNoInteractions(admission, persistence);
    }

    @Test
    void uncertainCommitWithoutRecordDoesNotPretendAcceptanceOrRelease() {
        doAnswer(
                        call -> {
                            BookingOperationContext.current().setTransactionState("UNKNOWN");
                            throw new TransactionSystemException("commit status unknown");
                        })
                .when(persistence)
                .save(any());
        assertThrows(BookingException.class, () -> service.accept(input, "key", "hash"));
        verify(admission, never()).release(any());
    }

    @Test
    void committedRecordCanBeRecoveredAfterAnUncertainCommitException() {
        AtomicReference<BookingRequestTask> saved = new AtomicReference<>();
        when(requests.byKey(7L, "key")).thenAnswer(call -> saved.get());
        doAnswer(
                        call -> {
                            saved.set(call.getArgument(0));
                            BookingOperationContext.current().setTransactionState("UNKNOWN");
                            throw new TransactionSystemException("reply lost");
                        })
                .when(persistence)
                .save(any());
        BookingSubmissionDTO result = service.accept(input, "key", "hash");
        assertEquals(saved.get().getId(), result.getRequestId());
        verify(admission, never()).release(any());
    }

    @Test
    void resultNeverExposesAnotherUsersRecord() {
        BookingRequestTask other = task();
        other.setUserId(8L);
        when(requests.selectById(other.getId())).thenReturn(other);
        assertThrows(BookingException.class, () -> service.result(other.getId()));
    }

    @Test
    void cancelledOwnerMarkerIsReconciledBeforeAnotherAdmission() {
        BookingRequestTask cancelled = task();
        cancelled.setStatus("SUCCEEDED");
        cancelled.setActiveDate(null);
        cancelled.setBookingDate(BookingRules.now().toLocalDate().plusDays(1));
        when(admission.markerOwner(any())).thenReturn(cancelled.getId());
        when(requests.selectById(cancelled.getId())).thenReturn(cancelled);
        service.accept(input, "new-key", "hash");
        org.mockito.InOrder sequence = inOrder(admission, persistence);
        sequence.verify(admission).markerOwner(any());
        sequence.verify(admission).release(cancelled);
        sequence.verify(admission).reserve(any(), eq(1));
        sequence.verify(persistence).save(any());
    }

    @Test
    void activeOwnerIsNeverRemovedByReconciliation() {
        BookingRequestTask active = task();
        active.setActiveDate(BookingRules.now().toLocalDate().plusDays(1));
        active.setBookingDate(active.getActiveDate());
        when(admission.markerOwner(any())).thenReturn(active.getId());
        when(requests.selectById(active.getId())).thenReturn(active);
        service.accept(input, "new-key", "hash");
        verify(admission, never()).release(any());
    }

    private BookingRequestTask task() {
        BookingRequestTask task = new BookingRequestTask();
        task.setId("01234567890123456789012345678901");
        task.setUserId(7L);
        task.setRequestHash("hash");
        task.setStatus("ACCEPTED");
        return task;
    }
}
