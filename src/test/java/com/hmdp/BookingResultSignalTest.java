package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;

import com.hmdp.utils.BookingResultSignal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

class BookingResultSignalTest {

    @Test
    void notificationBeforeWaitingIsRetainedAndRepeatedNotificationsAreCoalesced()
            throws Exception {
        BookingResultSignal signal = new BookingResultSignal();
        long observed = signal.beginScan();
        for (int i = 0; i < 100; i++) {
            signal.wakeUp();
        }
        long started = System.nanoTime();
        signal.awaitChange(observed, 2000, 0);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);
        assertEquals(observed + 1, signal.beginScan());
    }

    @Test
    void notificationHonorsMinimumIntervalAndMissingNotificationStillTimesOut() throws Exception {
        BookingResultSignal signal = new BookingResultSignal();
        long observed = signal.beginScan();
        signal.wakeUp();
        long started = System.nanoTime();
        signal.awaitChange(observed, 2000, 150);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 120);
        observed = signal.beginScan();
        started = System.nanoTime();
        signal.awaitChange(observed, 150, 0);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed >= 120 && elapsed < 1500);
    }
}
