package com.hmdp.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.config.BookingMqProperties;
import com.hmdp.service.IBookingResultService;
import com.hmdp.utils.BookingResultEvent;
import com.hmdp.utils.BookingResultTask;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class RocketMqBookingMessageServiceTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private BookingMqProperties properties;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;
    private RocketMqBookingMessageService service;
    private CountDownLatch connected;

    @BeforeEach
    void setUp() throws Exception {
        properties = new BookingMqProperties();
        properties.setNameServer("127.0.0.1:9876");
        properties.setTopic("test-booking");
        producer = mock(DefaultMQProducer.class);
        consumer = mock(DefaultMQPushConsumer.class);
        connected = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            connected.countDown();
                            return null;
                        })
                .when(consumer)
                .start();
        service =
                spy(
                        new RocketMqBookingMessageService(
                                properties,
                                mock(BookingMessageListener.class),
                                mock(IBookingResultService.class)));
        doReturn(producer).when(service).createProducer();
        doReturn(consumer).when(service).createConsumer();
        doReturn(mock(DefaultMQPushConsumer.class))
                .when(service)
                .createResultConsumer(BookingResultTask.AUDIT);
        doReturn(mock(DefaultMQPushConsumer.class))
                .when(service)
                .createResultConsumer(BookingResultTask.STATS);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void absentNameServerStartsApplicationWithoutClientOrSynchronousFallback() {
        properties.setNameServer("");

        assertDoesNotThrow(service::start);

        assertFalse(service.isAvailable());
        assertThrows(IllegalStateException.class, () -> service.send(ID));
        verify(service, never()).createProducer();
        verify(service, never()).createConsumer();
    }

    @Test
    void sendsOnlyRequestIdAsBodyAndKeyAndAcceptsSendOk() throws Exception {
        SendResult success = new SendResult();
        success.setSendStatus(SendStatus.SEND_OK);
        when(producer.send(any(Message.class), anyLong())).thenReturn(success);
        startConnected();

        service.send(ID);

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(message.capture(), anyLong());
        assertEquals("test-booking", message.getValue().getTopic());
        assertEquals(ID, message.getValue().getKeys());
        assertArrayEquals(ID.getBytes(StandardCharsets.US_ASCII), message.getValue().getBody());
    }

    @Test
    void resultPublicationUsesOwnTopicAndStableBusinessIdentity() throws Exception {
        SendResult success = new SendResult();
        success.setSendStatus(SendStatus.SEND_OK);
        when(producer.send(any(Message.class), anyLong())).thenReturn(success);
        startConnected();
        service.sendResult(ID);
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(message.capture(), anyLong());
        assertEquals("booking-results", message.getValue().getTopic());
        assertEquals(ID + ":result:v1", message.getValue().getKeys());
        assertEquals(ID, BookingResultEvent.decode(message.getValue().getBody()));
    }

    @Test
    void nonSendOkIsNotReportedAsSuccessfullyPublished() throws Exception {
        SendResult uncertain = new SendResult();
        uncertain.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(producer.send(any(Message.class), anyLong())).thenReturn(uncertain);
        startConnected();

        assertThrows(IllegalStateException.class, () -> service.send(ID));
    }

    @Test
    void invalidRequestNeverReachesBroker() throws Exception {
        startConnected();

        assertThrows(IllegalArgumentException.class, () -> service.send("{phone:123}"));
        verify(producer, never()).send(any(Message.class), anyLong());
    }

    @Test
    void stoppingClosesBothClientsAndPreventsFurtherSend() throws Exception {
        startConnected();

        service.stop();

        assertFalse(service.isAvailable());
        assertFalse(service.isRunning());
        assertThrows(IllegalStateException.class, () -> service.send(ID));
        verify(consumer).shutdown();
        verify(producer).shutdown();
    }

    @Test
    void failedStartupIsContainedAndRetriedWithFreshClient() throws Exception {
        DefaultMQProducer failed = mock(DefaultMQProducer.class);
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            attempted.countDown();
                            throw new IllegalStateException("offline");
                        })
                .when(failed)
                .start();
        doReturn(failed, producer).when(service).createProducer();

        assertDoesNotThrow(service::start);
        assertTrue(attempted.await(3, TimeUnit.SECONDS));
        service.connect();

        assertTrue(service.isAvailable());
        verify(failed).shutdown();
        verify(producer).start();
    }

    private void startConnected() throws Exception {
        service.start();
        assertTrue(connected.await(3, TimeUnit.SECONDS));
        // 等待后台启动锁释放，保证两个客户端都已交付。
        service.connect();
        assertTrue(service.isAvailable());
    }
}
