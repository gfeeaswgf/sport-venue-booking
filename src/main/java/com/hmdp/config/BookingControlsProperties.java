package com.hmdp.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/** 预约请求控制配置，与场馆查询缓存、登录 Token 的期限分别管理。 */
@Data
@Component
@ConfigurationProperties(prefix = "booking.controls")
public class BookingControlsProperties {
    private int userLimit = 2;
    private long windowMillis = 1000;
    private int apiLimit = 0;
    private int slotLimit = 0;
    private long processingTtlSeconds = 120;
    private long successTtlSeconds = 259200;
    private long auditTtlSeconds = 259200;

    @PostConstruct
    public void validate() {
        if (userLimit < 1
                || userLimit > 10000
                || windowMillis < 1
                || apiLimit < 0
                || apiLimit > 10000
                || slotLimit < 0
                || slotLimit > 10000) {
            throw new IllegalArgumentException("预约限流次数和窗口必须有效，0仅用于关闭接口或场次限流");
        }
        if (processingTtlSeconds < 1 || successTtlSeconds < 1 || auditTtlSeconds < 1) {
            throw new IllegalArgumentException("预约处理中、结果缓存和审计期限必须大于0");
        }
    }
}
