package com.hmdp.utils;

/** 控制异常只携带固定原因码，审计不直接记录可能含敏感信息的异常消息。 */
public class BookingControlException extends BookingException {
    private final String reason;

    public BookingControlException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
