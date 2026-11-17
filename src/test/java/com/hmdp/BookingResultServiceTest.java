package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingOrderMapper;
import com.hmdp.mapper.BookingResultMapper;
import com.hmdp.service.impl.BookingResultServiceImpl;
import com.hmdp.utils.BookingResultRedis;
import com.hmdp.utils.BookingResultTask;
import com.hmdp.utils.BookingRules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class BookingResultServiceTest {

    private final BookingResultMapper requests = mock(BookingResultMapper.class);
    private final BookingOrderMapper orders = mock(BookingOrderMapper.class);
    private final BookingResultRedis redis = mock(BookingResultRedis.class);
    private BookingResultServiceImpl service;
    private BookingRequestTask task;

    @BeforeEach
    void setup() {
        service =
                new BookingResultServiceImpl(
                        requests, orders, redis, new BookingControlsProperties());
        task = new BookingRequestTask();
        task.setId("0123456789abcdef0123456789abcdef");
        task.setStatus("SUCCEEDED");
        task.setResultEventState("PENDING");
        task.setAuditState("PENDING");
        task.setStatsState("PENDING");
        task.setCompleteTime(BookingRules.now());
        task.setBookingDate(BookingRules.now().toLocalDate().plusDays(1));
        task.setVenueId(1L);
        when(requests.snapshot(task.getId())).thenReturn(task);
    }

    @Test
    void redisSuccessAndUnknownSqlCommitRetriesWithoutChangingOrderResult() {
        when(redis.writeAudit(task)).thenReturn(true);
        when(requests.finishAudit(eq(task.getId()), eq("DONE"), any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("commit reply lost"));
        assertThrows(
                IllegalStateException.class,
                () -> service.handle(task.getId(), BookingResultTask.AUDIT));
        assertEquals("SUCCEEDED", task.getStatus());
        verifyNoInteractions(orders);
    }

    @Test
    void oneCompletedGroupDoesNotPerformAnotherGroupsWork() {
        task.setAuditState("DONE");
        service.handle(task.getId(), BookingResultTask.AUDIT);
        verifyNoInteractions(redis, orders);
        verify(requests, never()).finishStats(anyString(), anyString(), any());
    }

    @Test
    void confirmationCrossingDeadlineSettlesExpiredInsteadOfAcknowledgingPending() {
        when(redis.writeAudit(task)).thenReturn(true);
        when(requests.finishAudit(eq(task.getId()), eq("DONE"), any())).thenReturn(0);
        when(requests.finishAudit(eq(task.getId()), eq("EXPIRED"), any())).thenReturn(1);
        assertDoesNotThrow(() -> service.handle(task.getId(), BookingResultTask.AUDIT));
    }

    @Test
    void redisFailureNeverMarksTaskDone() {
        when(redis.writeAudit(task)).thenThrow(new IllegalStateException("redis unavailable"));
        assertThrows(
                IllegalStateException.class,
                () -> service.handle(task.getId(), BookingResultTask.AUDIT));
        verify(requests, never()).finishAudit(anyString(), anyString(), any());
    }
}
