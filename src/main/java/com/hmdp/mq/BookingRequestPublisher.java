package com.hmdp.mq;

import com.hmdp.config.BookingMqProperties;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.service.IBookingCompletionService;
import com.hmdp.service.IBookingMessageService;
import com.hmdp.service.IBookingRequestWorkerService;
import com.hmdp.utils.BookingRules;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 已提交请求是持久待办；发送成功后仍保留下一轮恢复机会。 */
@Component
public class BookingRequestPublisher implements SmartLifecycle {
    private final BookingRequestMapper requests;
    private final IBookingMessageService messages;
    private final IBookingRequestWorkerService worker;
    private final IBookingCompletionService completion;
    private final BookingMqProperties properties;
    private final BookingMqLog log = new BookingMqLog(BookingRequestPublisher.class);
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public BookingRequestPublisher(
            BookingRequestMapper requests,
            IBookingMessageService messages,
            IBookingRequestWorkerService worker,
            IBookingCompletionService completion,
            BookingMqProperties properties) {
        this.requests = requests;
        this.messages = messages;
        this.worker = worker;
        this.completion = completion;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        // 独占一个有界后台线程，MQ网络等待不占用场次自动发布调度器。
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "booking-mq-publish");
                            thread.setDaemon(true);
                            return thread;
                        });
        long delay = Math.max(200, Math.min(1000, properties.getPublishDelayMillis()));
        scheduler.scheduleWithFixedDelay(this::publishDue, 0, delay, TimeUnit.MILLISECONDS);
    }

    public void publishDue() {
        try {
            // 1. 独立调度只读已提交记录，认领条件保证多实例不会同时发布。
            LocalDateTime now = BookingRules.now();
            List<BookingRequestTask> tasks =
                    requests.findDue(now, Math.max(1, Math.min(256, properties.getBatchSize())));
            for (BookingRequestTask task : tasks) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                // 2. 先补偿终态和关闭过期记录，MQ离线也不能跳过。
                if (localWork(task, now)) {
                    publish(task, now);
                }
            }
            // 3. 本批只给网络发送500毫秒预算，单次发送仍受SDK超时限制。
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            for (BookingRequestTask task : tasks) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                    return;
                }
                if (!localWork(task, now)) {
                    publish(task, now);
                }
            }
        } catch (Exception exception) {
            log.warn("扫描预约发布待办失败", exception);
        }
    }

    private void publish(BookingRequestTask task, LocalDateTime now) {
        try {
            if (requests.claim(task.getId(), now, now.plusSeconds(30)) == 0) {
                return;
            }
            // 终态成功完成审计后由completion标记不再扫描，异常保留本轮认领窗口。
            if (BookingMessageListener.terminal(task)) {
                completion.record(task);
                return;
            }
            // MQ离线也推进认领窗口，避免旧记录挡住后续过期任务。
            if (task.getExpireTime() != null && !task.getExpireTime().isAfter(now)) {
                BookingRequestTask closed = worker.fail(task.getId(), "预约请求已过期，请重新预约");
                if (BookingMessageListener.terminal(closed)) {
                    completion.record(closed);
                }
                return;
            }
            // 只在后台等待SEND_OK；发送异常保留ACCEPTED，30秒后再次恢复。
            if (messages.isAvailable()) {
                messages.send(task.getId());
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("预约待办发布或过期处理失败", exception);
        }
    }

    private boolean localWork(BookingRequestTask task, LocalDateTime now) {
        return BookingMessageListener.terminal(task)
                || (task.getExpireTime() != null && !task.getExpireTime().isAfter(now));
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
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
}
