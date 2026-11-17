package com.hmdp.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** MQ只负责通知，已受理请求始终以数据库为准。 */
@Data
@Component
@ConfigurationProperties(prefix = "booking.mq")
public class BookingMqProperties {

    private String nameServer = "";
    private String topic = "booking-requests";
    private String producerGroup = "booking-request-producer";
    private String consumerGroup = "booking-request-consumer";
    private int batchSize = 32;
    private int sendTimeoutMillis = 1000;
    private int publishDelayMillis = 500;

    private int resultIdleMaxDelayMillis = 2000;

    private String resultTopic = "booking-results";
    private String auditConsumerGroup = "booking-result-audit-consumer";
    private String statsConsumerGroup = "booking-result-stats-consumer";
}
