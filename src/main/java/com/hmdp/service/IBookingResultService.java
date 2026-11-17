package com.hmdp.service;

import com.hmdp.entity.BookingRequestTask;
import com.hmdp.utils.BookingResultTask;

public interface IBookingResultService {

    void handle(String requestId, BookingResultTask type);

    boolean expireTasks(BookingRequestTask task);
}
