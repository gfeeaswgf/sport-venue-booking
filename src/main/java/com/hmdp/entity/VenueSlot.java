package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("tb_venue_slot")
public class VenueSlot implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long courtId;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer capacity;
    private Integer bookedCount;
    private Boolean enabled;
    private LocalDateTime createTime;

    @TableField(exist = false)
    private Boolean available;

    @TableField(exist = false)
    private Integer remaining;

    @TableField(exist = false)
    private String statusText;
}
