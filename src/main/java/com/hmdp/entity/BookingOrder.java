package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 体育场馆预约业务实体。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_booking_order")
public class BookingOrder implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long userId;
    private Long venueId;
    private Long courtId;
    private Long slotId;
    private String venueName;
    private String courtName;
    private LocalDate bookingDate;

    @JsonIgnore
    private LocalDate activeDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String realName;
    private String studentNo;
    private String phone;

    @JsonIgnore
    private String companionsJson;
    private Integer peopleCount;
    private String status;
    private String verifyCode;
    private Integer cancelAdvanceMinutes;
    private LocalDateTime cancelTime;
    private LocalDateTime verifyTime;
    private LocalDateTime createTime;

    @TableField(exist = false)
    private Boolean canCancel;

    @TableField(exist = false)
    private Boolean canVerify;

    @TableField(exist = false)
    private String statusText;

    @TableField(exist = false)
    private java.util.List<com.hmdp.dto.BookingCompanionDTO> companions;
}
