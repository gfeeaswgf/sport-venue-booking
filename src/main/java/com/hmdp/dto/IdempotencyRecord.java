package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 幂等脚本只返回有限状态与订单编号，不反序列化任意业务对象。 */
@Data
@AllArgsConstructor
public class IdempotencyRecord {
    private String state;
    private String orderId;
}
