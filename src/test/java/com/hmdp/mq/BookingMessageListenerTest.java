package com.hmdp.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hmdp.entity.BookingRequestTask;
import com.hmdp.service.IBookingRequestWorkerService;
import com.hmdp.utils.BookingException;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

class BookingMessageListenerTest {

    private static final String ID = "0123456789abcdef0123456789abcdef";
    private IBookingRequestWorkerService worker;
    private BookingMessageListener listener;

    @BeforeEach
    void setup() {
        worker = mock(IBookingRequestWorkerService.class);
        listener = new BookingMessageListener(worker);
    }

    @Test
    void coreAcknowledgesWithoutCallingPostProcessing() {
        when(worker.process(ID)).thenReturn(task("SUCCEEDED"));
        assertEquals(
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                listener.consumeMessage(Collections.singletonList(message()), null));
        verifyNoMoreInteractionsAfterCore();
    }

    @Test
    void definiteBusinessFailureClosesBeforeAcknowledging() {
        when(worker.process(ID)).thenThrow(new BookingException("名额已满"));
        when(worker.fail(ID, "名额已满")).thenReturn(task("FAILED"));
        assertEquals(
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                listener.consumeMessage(Collections.singletonList(message()), null));
        verify(worker).fail(ID, "名额已满");
    }

    @Test
    void infrastructureFailureRetriesWithoutClosing() {
        when(worker.process(ID)).thenThrow(new IllegalStateException("database offline"));
        assertEquals(
                ConsumeConcurrentlyStatus.RECONSUME_LATER,
                listener.consumeMessage(Collections.singletonList(message()), null));
        verify(worker, never()).fail(anyString(), anyString());
    }

    @Test
    void acceptedRequestIsNotAcknowledged() {
        when(worker.process(ID)).thenReturn(task("ACCEPTED"));
        assertEquals(
                ConsumeConcurrentlyStatus.RECONSUME_LATER,
                listener.consumeMessage(Collections.singletonList(message()), null));
    }

    private void verifyNoMoreInteractionsAfterCore() {
        verify(worker).process(ID);
        verifyNoMoreInteractions(worker);
    }

    private BookingRequestTask task(String status) {
        BookingRequestTask task = new BookingRequestTask();
        task.setId(ID);
        task.setStatus(status);
        return task;
    }

    private MessageExt message() {
        MessageExt message = new MessageExt();
        message.setBody(ID.getBytes(StandardCharsets.US_ASCII));
        return message;
    }
}
