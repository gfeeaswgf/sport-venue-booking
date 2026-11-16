package com.hmdp.service;

import com.hmdp.entity.BookingRequestTask;

public interface IBookingCompletionService {
    void record(BookingRequestTask task);
}
