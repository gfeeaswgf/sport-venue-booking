package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.entity.BookingOrder;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.service.IBookingOrderService;
import com.hmdp.service.impl.BookingRequestWorkerServiceImpl;
import com.hmdp.utils.BookingAdmissionRedis;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRules;
import com.hmdp.utils.UserHolder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AsyncBookingWorkerTest {
    BookingRequestMapper requests;
    IBookingOrderService orders;
    BookingAdmissionRedis admission;
    BookingRequestWorkerServiceImpl worker;
    BookingRequestTask task;

    @BeforeEach
    void setup() {
        UserHolder.removeUser();
        requests = mock(BookingRequestMapper.class);
        orders = mock(IBookingOrderService.class);
        admission = mock(BookingAdmissionRedis.class);
        worker = new BookingRequestWorkerServiceImpl();
        ReflectionTestUtils.setField(worker, "requests", requests);
        ReflectionTestUtils.setField(worker, "orders", orders);
        ReflectionTestUtils.setField(worker, "admission", admission);
        task = new BookingRequestTask();
        task.setId("01234567890123456789012345678901");
        task.setUserId(7L);
        task.setStatus("ACCEPTED");
        task.setExpireTime(BookingRules.now().plusMinutes(10));
        when(requests.lockById(task.getId())).thenReturn(task);
    }

    @Test
    void consumerUsesPersistedIdentityAndMarksSuccessWithoutUserHolder() {
        when(orders.createAcceptedOrder(task)).thenReturn(new BookingOrder().setId(task.getId()));
        when(requests.succeed(eq(task.getId()), any())).thenReturn(1);
        BookingRequestTask result = worker.process(task.getId());
        assertEquals("SUCCEEDED", result.getStatus());
        verify(orders).createAcceptedOrder(task);
        verify(requests).succeed(eq(task.getId()), any());
        assertNull(UserHolder.getUser());
    }

    @Test
    void duplicateSuccessNeverCreatesAnotherOrder() {
        task.setStatus("SUCCEEDED");
        assertSame(task, worker.process(task.getId()));
        verifyNoInteractions(orders, admission);
    }

    @Test
    void unknownDatabaseFailurePropagatesForMqRetryInsteadOfBecomingBusinessFailure() {
        when(orders.createAcceptedOrder(task))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> worker.process(task.getId()));
        verify(requests, never()).fail(anyString(), anyString(), any());
        verifyNoInteractions(admission);
    }

    @Test
    void expiredRequestDoesNotCreateAnOrder() {
        task.setExpireTime(BookingRules.now().minusSeconds(1));
        assertThrows(BookingException.class, () -> worker.process(task.getId()));
        verifyNoInteractions(orders);
    }

    @Test
    void lateFailureCannotOverwriteACommittedOrder() {
        task.setStatus("SUCCEEDED");
        assertSame(task, worker.fail(task.getId(), "超时"));
        verify(requests, never()).fail(anyString(), anyString(), any());
        verifyNoInteractions(admission);
    }
}
