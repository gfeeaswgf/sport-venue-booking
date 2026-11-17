package com.hmdp.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.config.BookingMqProperties;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.mapper.BookingResultMapper;
import com.hmdp.service.IBookingMessageService;
import com.hmdp.service.IBookingRequestWorkerService;
import com.hmdp.service.IBookingResultService;
import com.hmdp.utils.BookingResultSignal;
import com.hmdp.utils.BookingRules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class BookingRequestPublisherTest {

    private static final String FIRST = "0123456789abcdef0123456789abcdef";
    private static final String SECOND = "abcdef0123456789abcdef0123456789";
    private final BookingRequestMapper requests = mock(BookingRequestMapper.class);
    private final BookingResultMapper results = mock(BookingResultMapper.class);
    private final IBookingMessageService messages = mock(IBookingMessageService.class);
    private final IBookingRequestWorkerService worker = mock(IBookingRequestWorkerService.class);
    private final IBookingResultService completion = mock(IBookingResultService.class);
    private final BookingMqProperties properties = new BookingMqProperties();
    private BookingRequestPublisher publisher;

    @BeforeEach
    void setup() {
        publisher =
                new BookingRequestPublisher(
                        requests, results, messages, worker, completion, properties);
        when(messages.isAvailable()).thenReturn(true);
        when(requests.findDue(any(), anyInt())).thenReturn(Collections.emptyList());
        when(results.findDue(anyString(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(requests.claim(anyString(), any(), any())).thenReturn(1);
        when(results.claim(anyString(), anyString(), any(), any())).thenReturn(1);
    }

    @AfterEach
    void stop() {
        publisher.stop();
    }

    @Test
    void expiredRequestClosesEvenWithBrokerOffline() {
        BookingRequestTask expired = task(FIRST);
        expired.setExpireTime(BookingRules.now().minusSeconds(1));
        when(requests.findDue(any(), anyInt())).thenReturn(Collections.singletonList(expired));
        when(messages.isAvailable()).thenReturn(false);
        publisher.publishDue();
        verify(worker).fail(eq(FIRST), anyString());
        verifyNoInteractions(completion, results);
    }

    @Test
    void unknownSendAndResultMarkFailureRetainRecoveryOpportunity() throws Exception {
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenReturn(Collections.singletonList(task(FIRST)));
        doThrow(new IllegalStateException("send reply lost"))
                .doNothing()
                .when(messages)
                .sendResult(FIRST);
        publisher.publishResults();
        verify(results, never()).published(anyString(), anyString(), any());
        publisher.publishResults();
        verify(messages, times(2)).sendResult(FIRST);
        verify(results).published(eq(FIRST), anyString(), any());
        verifyNoInteractions(worker);
    }

    @Test
    void failedClaimNeverSendsAndFinishedTasksNeverPublish() throws Exception {
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenReturn(Arrays.asList(task(FIRST), task(SECOND)));
        when(results.claim(eq(FIRST), anyString(), any(), any())).thenReturn(0);
        when(completion.expireTasks(argThat(value -> SECOND.equals(value.getId()))))
                .thenReturn(true);
        publisher.publishResults();
        verify(messages, never()).sendResult(anyString());
        verify(results, never()).published(anyString(), anyString(), any());
    }

    @Test
    void resultNetworkWaitDoesNotBlockRequestPublisher() throws Exception {
        CountDownLatch resultEntered = new CountDownLatch(1);
        CountDownLatch requestSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(requests.findDue(any(), anyInt())).thenReturn(Collections.singletonList(task(FIRST)));
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenReturn(Collections.singletonList(task(SECOND)));
        doAnswer(
                        invocation -> {
                            resultEntered.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            return null;
                        })
                .when(messages)
                .sendResult(SECOND);
        doAnswer(
                        invocation -> {
                            requestSent.countDown();
                            return null;
                        })
                .when(messages)
                .send(FIRST);
        try {
            publisher.start();
            assertTrue(resultEntered.await(2, TimeUnit.SECONDS));
            assertTrue(requestSent.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void batchOfOneStillGivesFailedEventsATurn() throws Exception {
        properties.setBatchSize(1);
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenReturn(Collections.singletonList(task(FIRST)));
        when(results.findDue(eq("FAILED"), any(), anyInt()))
                .thenReturn(Collections.singletonList(task(SECOND)));
        publisher.publishResults();
        publisher.publishResults();
        verify(messages).sendResult(FIRST);
        verify(messages).sendResult(SECOND);
    }

    @Test
    void emptyResultPollingBacksOffInsteadOfKeepingActiveFrequency() throws Exception {
        properties.setPublishDelayMillis(200);
        AtomicInteger scans = new AtomicInteger();
        CountDownLatch secondScan = new CountDownLatch(1);
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            if (scans.incrementAndGet() == 2) {
                                secondScan.countDown();
                            }
                            return Collections.emptyList();
                        });
        publisher.start();
        assertTrue(secondScan.await(3, TimeUnit.SECONDS));
        Thread.sleep(500);
        assertEquals(2, scans.get(), "空闲应退避，不能一直按活跃间隔查询");
    }

    @Test
    void committedWorkWakesIdlePublisherWithoutWaitingForIdleMaximum() throws Exception {
        properties.setPublishDelayMillis(200);
        BookingResultSignal signal = new BookingResultSignal();
        ReflectionTestUtils.setField(publisher, "resultSignal", signal);
        AtomicInteger scans = new AtomicInteger();
        AtomicBoolean ready = new AtomicBoolean();
        CountDownLatch thirdScan = new CountDownLatch(1);
        CountDownLatch sent = new CountDownLatch(1);
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            boolean available = ready.get();
                            if (scans.incrementAndGet() == 3) {
                                thirdScan.countDown();
                            }
                            return available
                                    ? Collections.singletonList(task(FIRST))
                                    : Collections.emptyList();
                        });
        doAnswer(
                        call -> {
                            sent.countDown();
                            return null;
                        })
                .when(messages)
                .sendResult(FIRST);
        publisher.start();
        assertTrue(thirdScan.await(3, TimeUnit.SECONDS));
        ready.set(true);
        for (int i = 0; i < 100; i++) {
            signal.wakeUp();
        }
        assertTrue(sent.await(1, TimeUnit.SECONDS));
    }

    @Test
    void periodicScanRecoversWorkEvenWithoutLocalNotification() throws Exception {
        properties.setPublishDelayMillis(200);
        properties.setResultIdleMaxDelayMillis(400);
        AtomicBoolean ready = new AtomicBoolean();
        CountDownLatch firstScan = new CountDownLatch(1);
        CountDownLatch sent = new CountDownLatch(1);
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            boolean available = ready.get();
                            firstScan.countDown();
                            return available
                                    ? Collections.singletonList(task(FIRST))
                                    : Collections.emptyList();
                        });
        doAnswer(
                        call -> {
                            sent.countDown();
                            return null;
                        })
                .when(messages)
                .sendResult(FIRST);
        publisher.start();
        assertTrue(firstScan.await(2, TimeUnit.SECONDS));
        ready.set(true);
        assertTrue(sent.await(2, TimeUnit.SECONDS));
    }

    @Test
    void resultQueryExceptionDoesNotTerminateTheRecoveryLoop() throws Exception {
        properties.setPublishDelayMillis(200);
        CountDownLatch sent = new CountDownLatch(1);
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenThrow(new IllegalStateException("temporary database failure"))
                .thenReturn(Collections.singletonList(task(FIRST)));
        doAnswer(
                        call -> {
                            sent.countDown();
                            return null;
                        })
                .when(messages)
                .sendResult(FIRST);
        publisher.start();
        assertTrue(sent.await(2, TimeUnit.SECONDS));
    }

    @Test
    void stoppedGenerationCannotResumePollingAfterRestart() throws Exception {
        properties.setPublishDelayMillis(200);
        AtomicReference<Thread> oldThread = new AtomicReference<>();
        AtomicInteger oldReentries = new AtomicInteger();
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch oldReleased = new CountDownLatch(1);
        CountDownLatch newEntered = new CountDownLatch(1);
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            Thread current = Thread.currentThread();
                            if (oldThread.compareAndSet(null, current)) {
                                oldEntered.countDown();
                                // 模拟暂不响应中断的JDBC调用，验证代际检查而不是依赖中断碰巧生效。
                                while (releaseOld.getCount() > 0) {
                                    try {
                                        releaseOld.await();
                                    } catch (InterruptedException ignored) {
                                        // 有意清除中断，模拟外部调用。
                                    }
                                }
                                oldReleased.countDown();
                            } else if (current == oldThread.get()) {
                                oldReentries.incrementAndGet();
                            } else {
                                newEntered.countDown();
                            }
                            return Collections.emptyList();
                        });
        try {
            publisher.start();
            assertTrue(oldEntered.await(2, TimeUnit.SECONDS));
            publisher.stop();
            publisher.start();
            assertTrue(newEntered.await(2, TimeUnit.SECONDS));
            releaseOld.countDown();
            assertTrue(oldReleased.await(1, TimeUnit.SECONDS));
            Thread.sleep(1000);
            assertEquals(0, oldReentries.get());
        } finally {
            releaseOld.countDown();
        }
    }

    @Test
    void continuousWakeupsCannotTurnScanningIntoOneQueryPairPerOrder() throws Exception {
        properties.setPublishDelayMillis(200);
        BookingResultSignal signal = new BookingResultSignal();
        ReflectionTestUtils.setField(publisher, "resultSignal", signal);
        List<Long> starts = new CopyOnWriteArrayList<>();
        CountDownLatch scanned = new CountDownLatch(5);
        when(results.findDue(eq("SUCCEEDED"), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            starts.add(System.nanoTime());
                            scanned.countDown();
                            return Collections.emptyList();
                        });
        ScheduledExecutorService waker = Executors.newSingleThreadScheduledExecutor();
        try {
            publisher.start();
            waker.scheduleWithFixedDelay(signal::wakeUp, 0, 1, TimeUnit.MILLISECONDS);
            assertTrue(scanned.await(3, TimeUnit.SECONDS));
            for (int index = 1; index < starts.size(); index++) {
                long gap = TimeUnit.NANOSECONDS.toMillis(starts.get(index) - starts.get(index - 1));
                assertTrue(gap >= 175, "持续通知不能突破200ms活跃扫描间隔");
            }
        } finally {
            waker.shutdownNow();
        }
    }

    @Test
    void scanFailureDoesNotTerminateScheduledRecovery() {
        when(requests.findDue(any(), anyInt()))
                .thenThrow(new IllegalStateException("database offline"));
        assertDoesNotThrow(publisher::publishDue);
    }

    private BookingRequestTask task(String id) {
        BookingRequestTask task = new BookingRequestTask();
        task.setId(id);
        task.setStatus("ACCEPTED");
        task.setExpireTime(BookingRules.now().plusMinutes(5));
        return task;
    }
}
