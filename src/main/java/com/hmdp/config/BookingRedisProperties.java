package com.hmdp.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 预约 Redis 配置；登录与预约 Key 分别管理。 */
@Data
@Component
@ConfigurationProperties(prefix = "booking.redis")
public class BookingRedisProperties {
    private boolean enabled = true;
    private String namespace = "";

    /** 基础信息默认不过期；大于 0 时可指定过期秒数。 */
    private long catalogTtlSeconds = 0;

    private long slotsTtlSeconds = 20;
    private long pendingSeconds = 120;
}
