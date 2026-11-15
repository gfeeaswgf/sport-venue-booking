package com.hmdp.dto;

import lombok.Data;

import java.math.BigDecimal;

/** Redis 短期操作记录，只接收允许记录的字段，不保存请求体或个人资料。 */
@Data
public class OperationLogDTO {
    private String requestId;
    private Long userId;
    private String operation;
    private String targetType;
    private String targetId;
    private String httpMethod;
    private String pathPattern;
    private String outcome;
    private String executionType;
    private String idempotencyKeyHash;
    private BigDecimal durationMs;
    private String reasonCode;
    private String exceptionType;
    private String createTime;
}
