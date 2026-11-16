package com.hmdp.service;

import com.hmdp.entity.BookingRequestTask;

public interface IBookingRequestWorkerService {
    BookingRequestTask process(String requestId);

    BookingRequestTask fail(String requestId, String reason);
}
