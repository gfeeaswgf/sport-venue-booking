package com.hmdp.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.hmdp.entity.BookingRequestTask;
import com.hmdp.service.IBookingCompletionService;
import com.hmdp.service.IBookingRequestWorkerService;
import com.hmdp.utils.BookingException;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

class BookingMessageListenerTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private IBookingRequestWorkerService worker;
    private IBookingCompletionService completion;
    private BookingMessageListener listener;

    @BeforeEach
    void setUp() {
        worker = mock(IBookingRequestWorkerService.class);
        completion = mock(IBookingCompletionService.class);
        listener = new BookingMessageListener(worker, completion);
    }

    @Test
    void acknowledgesOnlyAfterCommittedResultAndCompletion() {
        BookingRequestTask task = terminal("SUCCEEDED");
        when(worker.process(ID)).thenReturn(task);

        assertEquals(
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                listener.consumeMessage(Collections.singletonList(message(ID)), null));
        InOrder order = inOrder(worker, completion);
        order.verify(worker).process(ID);
        order.verify(completion).record(task);
    }

    @Test
    void definiteBusinessFailureClosesInSeparateWorkerCallBeforeCompletion() {
        BookingRequestTask task = terminal("FAILED");
        when(worker.process(ID)).thenThrow(new BookingException("名额已满"));
        when(worker.fail(ID, "名额已满")).thenReturn(task);

        assertEquals(
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                listener.consumeMessage(Collections.singletonList(message(ID)), null));
        InOrder order = inOrder(worker, completion);
        order.verify(worker).process(ID);
        order.verify(worker).fail(ID, "名额已满");
        order.verify(completion).record(task);
    }

    @Test
    void infrastructureFailureRetriesWithoutClosingRequest() {
        when(worker.process(ID)).thenThrow(new IllegalStateException("database offline"));

        assertEquals(
                ConsumeConcurrentlyStatus.RECONSUME_LATER,
                listener.consumeMessage(Collections.singletonList(message(ID)), null));
        verify(worker, never()).fail(anyString(), anyString());
        verifyNoInteractions(completion);
    }

    @Test
    void completionFailureRetriesAlreadyCommittedRequest() {
        BookingRequestTask task = terminal("SUCCEEDED");
        when(worker.process(ID)).thenReturn(task);
        doThrow(new IllegalStateException("redis offline")).when(completion).record(task);

        assertEquals(
                ConsumeConcurrentlyStatus.RECONSUME_LATER,
                listener.consumeMessage(Collections.singletonList(message(ID)), null));
        verify(worker, never()).fail(anyString(), anyString());
    }

    @Test
    void acceptedResultIsNeverAcknowledged() {
        when(worker.process(ID)).thenReturn(terminal("ACCEPTED"));

        assertEquals(
                ConsumeConcurrentlyStatus.RECONSUME_LATER,
                listener.consumeMessage(Collections.singletonList(message(ID)), null));
        verifyNoInteractions(completion);
    }

    @Test
    void malformedAndMissingRequestsAreSafelyIgnored() {
        when(worker.process(ID)).thenReturn(null);
        MessageExt badBytes = new MessageExt();
        badBytes.setBody(new byte[] {(byte) 0xff});

        assertEquals(
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                listener.consumeMessage(Arrays.asList(message("{}"), badBytes, message(ID)), null));
        verify(worker).process(ID);
        verifyNoMoreInteractions(worker);
        verifyNoInteractions(completion);
    }

    private BookingRequestTask terminal(String status) {
        BookingRequestTask task = new BookingRequestTask();
        task.setId(ID);
        task.setStatus(status);
        return task;
    }

    private MessageExt message(String id) {
        MessageExt message = new MessageExt();
        message.setBody(id.getBytes(StandardCharsets.US_ASCII));
        return message;
    }
}
