package com.hmdp.aspect;

import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.utils.BookingOperationContext;
import com.hmdp.utils.BookingRequestRedis;
import com.hmdp.utils.BookingRules;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(30)
public class RateLimitAspect {
    private final BookingRequestRedis redis;

    public RateLimitAspect(BookingRequestRedis redis) {
        this.redis = redis;
    }

    @Around("@annotation(com.hmdp.annotation.RateLimit)")
    public Object check(ProceedingJoinPoint point) throws Throwable {
        // 每次预约接口请求都计数，包括已经成功结果的重试。
        BookingRequestDTO request = (BookingRequestDTO) point.getArgs()[0];
        Long slotId = request == null ? null : request.getSlotId();
        redis.checkRate(
                BookingRules.currentUserId(),
                slotId,
                BookingOperationContext.current().getRequestId());
        return point.proceed();
    }
}
