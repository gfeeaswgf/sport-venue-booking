package com.hmdp.dto;

import lombok.Data;

/** 当前登录用户的预约资料，仅通过私有接口返回。 */
@Data
public class BookingProfileDTO {
    private Long id;
    private String nickName;
    private String icon;
    private String phone;
    private String realName;
    private String studentNo;
    private Boolean isAdmin;
}
