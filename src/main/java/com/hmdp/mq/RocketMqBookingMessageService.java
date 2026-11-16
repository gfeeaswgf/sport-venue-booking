package com.hmdp.mq;

import com.hmdp.config.BookingMqProperties;
import com.hmdp.service.IBookingMessageService;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 客户端后台启动及重连，HTTP受理线程永远不等待MQ。 */
@Service
public class RocketMqBookingMessageService implements IBookingMessageService, SmartLifecycle {
    private final BookingMqProperties properties;
    private final BookingMessageListener listener;
    private final BookingMqLog log = new BookingMqLog(RocketMqBookingMessageService.class);
    private volatile boolean running;
    private volatile DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;
    private ScheduledExecutorService connector;

    public RocketMqBookingMessageService(
            BookingMqProperties properties, BookingMessageListener listener) {
        this.properties = properties;
        this.listener = listener;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (properties.getNameServer() == null || properties.getNameServer().trim().isEmpty()) {
            return;
        }
        // 1. 仅一个后台重连线程，连接失败不能阻塞Spring启动。
        connector =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "booking-mq-connect");
                            thread.setDaemon(true);
                            return thread;
                        });
        connector.scheduleWithFixedDelay(this::connect, 0, 10, TimeUnit.SECONDS);
    }

    synchronized void connect() {
        if (!running) {
            return;
        }
        // 2. 启动失败的SDK实例不可复用，释放后下轮重建。
        if (producer == null) {
            DefaultMQProducer candidate = null;
            try {
                candidate = createProducer();
                candidate.setNamesrvAddr(properties.getNameServer().trim());
                candidate.setVipChannelEnabled(false);
                candidate.setClientCallbackExecutorThreads(2);
                candidate.setMqClientApiTimeout(timeoutMillis());
                candidate.setSendMsgTimeout(timeoutMillis());
                candidate.setRetryTimesWhenSendFailed(0);
                candidate.start();
                producer = candidate;
            } catch (Exception exception) {
                closeProducer(candidate);
                log.warn("预约MQ生产者启动失败", exception);
            }
        }
        if (consumer == null) {
            DefaultMQPushConsumer candidate = null;
            try {
                candidate = createConsumer();
                candidate.setNamesrvAddr(properties.getNameServer().trim());
                candidate.setVipChannelEnabled(false);
                candidate.setClientCallbackExecutorThreads(2);
                candidate.setMqClientApiTimeout(timeoutMillis());
                candidate.setConsumeThreadMin(2);
                candidate.setConsumeThreadMax(4);
                candidate.setConsumeMessageBatchMaxSize(1);
                candidate.setPullBatchSize(16);
                candidate.setPullThresholdForQueue(32);
                candidate.setAwaitTerminationMillisWhenShutdown(5000);
                candidate.subscribe(properties.getTopic(), "*");
                candidate.registerMessageListener(listener);
                candidate.start();
                consumer = candidate;
            } catch (Exception exception) {
                closeConsumer(candidate);
                log.warn("预约MQ消费者启动失败", exception);
            }
        }
    }

    protected DefaultMQProducer createProducer() {
        return new DefaultMQProducer(properties.getProducerGroup());
    }

    protected DefaultMQPushConsumer createConsumer() {
        return new DefaultMQPushConsumer(properties.getConsumerGroup());
    }

    @Override
    public boolean isAvailable() {
        return running && producer != null;
    }

    @Override
    public void send(String requestId) throws Exception {
        if (!BookingMessageListener.validRequestId(requestId)) {
            throw new IllegalArgumentException("预约请求ID格式错误");
        }
        DefaultMQProducer current = producer;
        if (!running || current == null) {
            throw new IllegalStateException("预约MQ尚未连接");
        }
        // 3. 消息只含ID；SEND_OK以外结果也留给持久待办重试。
        Message message =
                new Message(properties.getTopic(), requestId.getBytes(StandardCharsets.US_ASCII));
        message.setKeys(requestId);
        SendResult result = current.send(message, timeoutMillis());
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("预约MQ发送尚未确认");
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (connector != null) {
            connector.shutdownNow();
            connector = null;
        }
        closeConsumer(consumer);
        consumer = null;
        DefaultMQProducer current = producer;
        producer = null;
        closeProducer(current);
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private int timeoutMillis() {
        return Math.max(200, Math.min(5000, properties.getSendTimeoutMillis()));
    }

    private void closeProducer(DefaultMQProducer client) {
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception exception) {
                log.warn("预约MQ生产者关闭失败", exception);
            }
        }
    }

    private void closeConsumer(DefaultMQPushConsumer client) {
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception exception) {
                log.warn("预约MQ消费者关闭失败", exception);
            }
        }
    }
}
