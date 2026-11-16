package com.hmdp.aspect;

import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.utils.BookingControlException;
import com.hmdp.utils.BookingOperationContext;
import com.hmdp.utils.BookingRequestFingerprint;
import com.hmdp.utils.BookingRules;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/** 统一校验幂等请求头，原子持久化幂等由预约请求表唯一索引实现。 */
@Aspect
@Component
@Order(40)
public class IdempotentAspect {
    @Around("@annotation(com.hmdp.annotation.Idempotent)")
    public Object execute(ProceedingJoinPoint point) throws Throwable {
        BookingRules.currentUserId();
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String key =
                attributes == null ? null : attributes.getRequest().getHeader("Idempotency-Key");
        if (key == null || !key.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new BookingControlException("INVALID_KEY", "请提供有效的Idempotency-Key请求标识");
        }

        // 摘要在服务层规范化/冻结DTO前计算，重试始终对应同一份业务内容。
        String fingerprint = BookingRequestFingerprint.of((BookingRequestDTO) point.getArgs()[0]);
        BookingOperationContext.State context = BookingOperationContext.current();
        context.setIdempotencyKey(key.toLowerCase(Locale.ROOT));
        context.setRequestFingerprint(fingerprint);
        context.setIdempotencyKeyHash(BookingRequestFingerprint.hash(context.getIdempotencyKey()));
        return point.proceed();
    }
}
