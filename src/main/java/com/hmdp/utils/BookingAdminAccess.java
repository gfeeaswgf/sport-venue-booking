package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** 管理员由部署配置指定，资料编辑不能更改权限。 */
@Component
public class BookingAdminAccess {
    @Value("${booking.admin-user-ids:}")
    private String adminUserIds = "";

    public boolean isAdmin(Long userId) {
        return userId != null
                && Arrays.stream(adminUserIds.split(","))
                        .map(String::trim)
                        .anyMatch(userId.toString()::equals);
    }
}
