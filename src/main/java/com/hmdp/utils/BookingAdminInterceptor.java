package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class BookingAdminInterceptor implements HandlerInterceptor {
    private final BookingAdminAccess access;

    public BookingAdminInterceptor(BookingAdminAccess access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        if (!access.isAdmin(UserHolder.getUser().getId())) {
            response.setStatus(403);
            return false;
        }
        return true;
    }
}
