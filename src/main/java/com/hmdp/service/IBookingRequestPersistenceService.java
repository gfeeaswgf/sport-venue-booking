package com.hmdp.service;

import com.hmdp.entity.BookingRequestTask;

public interface IBookingRequestPersistenceService {
    void save(BookingRequestTask task);
}
