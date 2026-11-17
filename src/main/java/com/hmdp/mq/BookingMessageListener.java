package com.hmdp.mq;

import com.hmdp.entity.BookingRequestTask;
import com.hmdp.service.IBookingRequestWorkerService;
import com.hmdp.utils.BookingException;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 消费线程只使用持久请求，不读取登录线程的UserHolder。 */
@Component
public class BookingMessageListener implements MessageListenerConcurrently {
    private final IBookingRequestWorkerService worker;
    private final BookingMqLog log = new BookingMqLog(BookingMessageListener.class);

    public BookingMessageListener(IBookingRequestWorkerService worker) {
        this.worker = worker;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            // 1. 只接受固定长度ASCII请求ID，不解析个人资料或任意对象。
            byte[] body = message == null ? null : message.getBody();
            if (body == null || body.length != 32) {
                log.warn("忽略非法预约消息", null);
                continue;
            }
            String requestId = new String(body, StandardCharsets.US_ASCII);
            if (!validRequestId(requestId)) {
                log.warn("忽略非法预约消息", null);
                continue;
            }
            try {
                // 2. process事务代理已正常提交或回滚，业务失败另开事务关闭。
                BookingRequestTask task;
                try {
                    task = worker.process(requestId);
                } catch (BookingException exception) {
                    task = worker.fail(requestId, exception.getMessage());
                }
                if (task == null) {
                    log.warn("忽略不存在的预约请求", null);
                    continue;
                }
                if (!terminal(task)) {
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
                // 3. 核心事务已提交，结果待办也已登记；不等待审计、统计和MQ发送。
            } catch (Exception exception) {
                log.warn("预约消息处理暂未完成", exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    static boolean validRequestId(String requestId) {
        return requestId != null && requestId.matches("[0-9a-fA-F]{32}");
    }

    static boolean terminal(BookingRequestTask task) {
        return task != null
                && ("SUCCEEDED".equals(task.getStatus()) || "FAILED".equals(task.getStatus()));
    }
}
