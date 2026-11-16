package com.hmdp.service;

public interface IBookingMessageService {
    boolean isAvailable();

    void send(String requestId) throws Exception;
}
