package com.hmdp.mq;

import com.hmdp.service.IBookingResultService;
import com.hmdp.utils.BookingResultEvent;
import com.hmdp.utils.BookingResultTask;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.List;

/** 每个实例对应一个消费组；一组异常不阻塞另一组的消息确认。 */
public class BookingResultMessageListener implements MessageListenerConcurrently {

    private final IBookingResultService results;
    private final BookingResultTask type;
    private final BookingMqLog log = new BookingMqLog(BookingResultMessageListener.class);

    public BookingResultMessageListener(IBookingResultService results, BookingResultTask type) {
        this.results = results;
        this.type = type;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        try {
            for (MessageExt message : messages) {
                String requestId =
                        BookingResultEvent.decode(message == null ? null : message.getBody());
                results.handle(requestId, type);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Exception exception) {
            log.warn("预约结果任务暂未完成 group=" + type.name(), exception);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }
}
