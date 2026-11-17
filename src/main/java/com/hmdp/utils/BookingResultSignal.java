package com.hmdp.utils;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 本机合并唤醒提示，不保存任务；消息可靠性始终由数据库待办保证。 */
@Component
public class BookingResultSignal {

    private long version;
    private boolean pending;

    public synchronized long beginScan() {
        pending = false;
        return version;
    }

    public synchronized void wakeUp() {
        // 每轮最多通知一次，避免高并发提交让等待线程反复唤醒。
        if (!pending) {
            version++;
            pending = true;
            notifyAll();
        }
    }

    public synchronized void awaitChange(
            long observedVersion, long timeoutMillis, long minimumMillis)
            throws InterruptedException {
        if (minimumMillis < 0 || timeoutMillis < minimumMillis) {
            throw new IllegalArgumentException("结果扫描等待时间无效");
        }
        long started = System.nanoTime();
        long minimum = started + TimeUnit.MILLISECONDS.toNanos(minimumMillis);
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            long now = System.nanoTime();
            if (now >= deadline || version != observedVersion && now >= minimum) {
                return;
            }
            long remaining = (version != observedVersion ? minimum : deadline) - now;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }
}
