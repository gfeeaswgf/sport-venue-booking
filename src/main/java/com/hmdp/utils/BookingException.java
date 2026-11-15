package com.hmdp.utils;

/** 可向用户展示的预约业务错误。 */
public class BookingException extends RuntimeException {
    public BookingException(String message) {
        super(message);
    }
}
