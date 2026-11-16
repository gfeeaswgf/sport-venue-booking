package com.hmdp.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/** 外部依赖故障按组件限流记录，避免重试打印请求内容或个人资料。 */
final class BookingMqLog {
    private final Logger logger;
    private final AtomicLong nextWarning = new AtomicLong(Long.MIN_VALUE);

    BookingMqLog(Class<?> owner) {
        logger = LoggerFactory.getLogger(owner);
    }

    void warn(String operation, Exception exception) {
        long now = System.nanoTime();
        long next = nextWarning.get();
        if (now >= next && nextWarning.compareAndSet(next, now + 30_000_000_000L)) {
            logger.warn(
                    "{}，稍后重试或检查配置；异常类型={}",
                    operation,
                    exception == null ? "INVALID_MESSAGE" : exception.getClass().getSimpleName());
        }
    }
}
