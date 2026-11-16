package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 持久化受理记录，同时是待发送任务；不随Redis缓存三天到期而删除。 */
@Data
@TableName("tb_booking_request")
public class BookingRequestTask {
    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;
    private String idempotencyKey;
    private String requestHash;
    private Long venueId;
    private Long courtId;
    private Long slotId;
    private LocalDate bookingDate;
    private LocalDate activeDate;
    private String realName;
    private String studentNo;
    private String phone;
    private String companionsJson;
    private String status;
    private String failureReason;
    private Integer publishCount;
    private LocalDateTime nextPublishTime;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime completeTime;
}
