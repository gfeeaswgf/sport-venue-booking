package com.hmdp.utils;

public class RedisConstants {
    public static final String BOOKING_CACHE_KEY = "cache:booking:";
    public static final String BOOKING_STOCK_KEY = "booking:stock:";
    public static final String BOOKING_USER_KEY = "booking:user:";
    public static final String BOOKING_RATE_KEY = "booking:rate:";
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;
}
