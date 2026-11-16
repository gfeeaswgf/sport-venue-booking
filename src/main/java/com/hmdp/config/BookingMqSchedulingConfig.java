package com.hmdp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** MQ恢复调度不依赖场次自动发布开关。 */
@Configuration
@EnableScheduling
public class BookingMqSchedulingConfig {}
