package com.hmdp.mq;

import com.hmdp.config.BookingMqProperties;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.mapper.BookingResultMapper;
import com.hmdp.service.IBookingMessageService;
import com.hmdp.service.IBookingRequestWorkerService;
import com.hmdp.service.IBookingResultService;
import com.hmdp.utils.BookingResultSignal;
import com.hmdp.utils.BookingRules;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Resource;

/** 复用持久请求待办；请求与结果各有有界发送线程，不互相排队等网络。 */
@Component
public class BookingRequestPublisher implements SmartLifecycle {

    private final BookingRequestMapper requests;
    private final BookingResultMapper results;
    private final IBookingMessageService messages;
    private final IBookingRequestWorkerService worker;
    private final IBookingResultService completion;
    private final BookingMqProperties properties;
    private final BookingMqLog log = new BookingMqLog(BookingRequestPublisher.class);

    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private boolean preferFailed;
    private volatile long lifecycleVersion;

    @Resource
    private BookingResultSignal resultSignal = new BookingResultSignal();

    public BookingRequestPublisher(
            BookingRequestMapper requests,
            BookingResultMapper results,
            IBookingMessageService messages,
            IBookingRequestWorkerService worker,
            IBookingResultService completion,
            BookingMqProperties properties) {
        this.requests = requests;
        this.results = results;
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
        long version = ++lifecycleVersion;
        AtomicInteger sequence = new AtomicInteger();
        scheduler =
                Executors.newScheduledThreadPool(
                        2,
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "booking-mq-publish-" + sequence.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        });
        long delay = Math.max(200, Math.min(1000, properties.getPublishDelayMillis()));
        scheduler.scheduleWithFixedDelay(this::publishDue, 0, delay, TimeUnit.MILLISECONDS);
        scheduler.execute(() -> runResultPublisher(version, delay));
    }

    private void runResultPublisher(long version, long activeDelay) {
        long idleMaximum =
                Math.max(activeDelay, Math.min(30000, properties.getResultIdleMaxDelayMillis()));
        long delay = activeDelay;
        while (running && lifecycleVersion == version && !Thread.currentThread().isInterrupted()) {
            long started = System.nanoTime();
            long observed = resultSignal.beginScan();
            boolean workFound = publishResults();
            delay = workFound ? activeDelay : Math.min(idleMaximum, delay * 2);
            // 唤醒只打断空闲长等待；保持最小扫描间隔，不能每单增加两条查询。
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long minimum = Math.max(0, activeDelay - elapsed);
            try {
                resultSignal.awaitChange(observed, delay, minimum);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void publishDue() {
        try {
            LocalDateTime now = BookingRules.now();
            List<BookingRequestTask> tasks = requests.findDue(now, batchSize());
            // 1. MQ离线也先关闭本批到期请求，只登记结果事件，不执行后处理。
            for (BookingRequestTask task : tasks) {
                if (expired(task, now)) {
                    dispatchRequest(task, now);
                }
            }
            long budget = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            for (BookingRequestTask task : tasks) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= budget) {
                    return;
                }
                if (!expired(task, now)) {
                    dispatchRequest(task, now);
                }
            }
        } catch (Exception exception) {
            log.warn("扫描预约请求待办失败", exception);
        }
    }

    boolean publishResults() {
        try {
            LocalDateTime now = BookingRules.now();
            int limit = batchSize();
            List<BookingRequestTask> succeeded = results.findDue("SUCCEEDED", now, limit);
            List<BookingRequestTask> failed = results.findDue("FAILED", now, limit);
            List<BookingRequestTask> first = preferFailed ? failed : succeeded;
            List<BookingRequestTask> second = preferFailed ? succeeded : failed;
            preferFailed = !preferFailed;
            List<BookingRequestTask> tasks = new ArrayList<>();
            // 2. 两种终态交替取，最多发送一个批次，避免失败事件长期饥饿。
            for (int index = 0;
                    tasks.size() < limit && (index < first.size() || index < second.size());
                    index++) {
                if (index < first.size()) {
                    tasks.add(first.get(index));
                }
                if (tasks.size() < limit && index < second.size()) {
                    tasks.add(second.get(index));
                }
            }
            long budget = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            for (BookingRequestTask task : tasks) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= budget) {
                    return !tasks.isEmpty();
                }
                dispatchResult(task, now);
            }
            return !tasks.isEmpty();
        } catch (Exception exception) {
            log.warn("扫描预约结果待办失败", exception);
            return true;
        }
    }

    private void dispatchRequest(BookingRequestTask task, LocalDateTime now) {
        try {
            if (requests.claim(task.getId(), now, now.plusSeconds(30)) == 0) {
                return;
            }
            if (expired(task, now)) {
                worker.fail(task.getId(), "预约请求已过期，请重新预约");
            } else if (messages.isAvailable()) {
                messages.send(task.getId());
            }
        } catch (Exception exception) {
            preserveInterrupt(exception);
            log.warn("预约请求发布或关闭暂未完成", exception);
        }
    }

    private void dispatchResult(BookingRequestTask task, LocalDateTime now) {
        try {
            String token = UUID.randomUUID().toString().replace("-", "");
            if (results.claim(task.getId(), token, now, now.plusSeconds(30)) == 0) {
                return;
            }
            // 3. 只做期限结算，Redis效果和COUNT仍属于各自消费组。
            if (completion.expireTasks(task)) {
                return;
            }
            if (messages.isAvailable()) {
                messages.sendResult(task.getId());
                // 4. 令牌及未完成条件保护：消费者先完成或旧发送者迟到均不重开。
                results.published(task.getId(), token, BookingRules.now());
            }
        } catch (Exception exception) {
            preserveInterrupt(exception);
            log.warn("预约结果发布暂未确认，保留持久恢复机会", exception);
        }
    }

    private int batchSize() {
        return Math.max(1, Math.min(256, properties.getBatchSize()));
    }

    private boolean expired(BookingRequestTask task, LocalDateTime now) {
        return task.getExpireTime() != null && !task.getExpireTime().isAfter(now);
    }

    private void preserveInterrupt(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        lifecycleVersion++;
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
