package com.hmdp.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hmdp.service.IBookingResultService;
import com.hmdp.utils.BookingResultEvent;
import com.hmdp.utils.BookingResultTask;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.util.Collections;

class BookingResultMessageListenerTest {

    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test
    void failingAuditDoesNotPreventStatisticsFromAcknowledgingSameEvent() {
        IBookingResultService service = mock(IBookingResultService.class);
        doThrow(new IllegalStateException("audit offline"))
                .when(service)
                .handle(ID, BookingResultTask.AUDIT);
        BookingResultMessageListener audit =
                new BookingResultMessageListener(service, BookingResultTask.AUDIT);
        BookingResultMessageListener stats =
                new BookingResultMessageListener(service, BookingResultTask.STATS);
        MessageExt message = new MessageExt();
        message.setBody(BookingResultEvent.encode(ID));
        assertEquals(
                ConsumeConcurrentlyStatus.RECONSUME_LATER,
                audit.consumeMessage(Collections.singletonList(message), null));
        assertEquals(
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                stats.consumeMessage(Collections.singletonList(message), null));
    }

    @Test
    void malformedEventIsRetriedForDeadLetterInvestigationWithoutRunningEffects() {
        IBookingResultService service = mock(IBookingResultService.class);
        BookingResultMessageListener listener =
                new BookingResultMessageListener(service, BookingResultTask.AUDIT);
        MessageExt message = new MessageExt();
        message.setBody(new byte[512]);
        assertEquals(
                ConsumeConcurrentlyStatus.RECONSUME_LATER,
                listener.consumeMessage(Collections.singletonList(message), null));
        verifyNoInteractions(service);
    }
}
