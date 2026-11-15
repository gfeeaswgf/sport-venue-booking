package com.hmdp.config;

import com.hmdp.utils.BookingOperationContext;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** 为一次 HTTP 访问建立标识，和前端重试使用的幂等标识分别管理。 */
@Component
public class BookingRequestFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        BookingOperationContext.State context = BookingOperationContext.open();
        response.setHeader("X-Request-Id", context.getRequestId());
        try {
            chain.doFilter(request, response);
        } finally {
            BookingOperationContext.clear();
        }
    }
}
