package com.hmdp.aspect;

import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.IdempotencyRecord;
import com.hmdp.dto.Result;
import com.hmdp.utils.BookingControlException;
import com.hmdp.utils.BookingOperationContext;
import com.hmdp.utils.BookingRequestFingerprint;
import com.hmdp.utils.BookingRequestRedis;
import com.hmdp.utils.BookingRules;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Slf4j
@Aspect
@Component
@Order(40)
public class IdempotentAspect {
    private final BookingRequestRedis redis;

    public IdempotentAspect(BookingRequestRedis redis) {
        this.redis = redis;
    }

    @Around("@annotation(com.hmdp.annotation.Idempotent)")
    public Object execute(ProceedingJoinPoint point) throws Throwable {
        // 1.先校验请求标识与内容，用户身份始终读取服务端登录上下文。
        Long userId = BookingRules.currentUserId();
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String key =
                attributes == null ? null : attributes.getRequest().getHeader("Idempotency-Key");
        if (key == null || !key.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new BookingControlException("INVALID_KEY", "请提供有效的Idempotency-Key请求标识");
        }
        key = key.toLowerCase(java.util.Locale.ROOT);
        String fingerprint = BookingRequestFingerprint.of((BookingRequestDTO) point.getArgs()[0]);
        BookingOperationContext.State context = BookingOperationContext.current();
        context.setIdempotencyKeyHash(BookingRequestFingerprint.hash(key));
        String owner = UUID.randomUUID().toString();

        // 2.原子判断处理中、内容冲突和成功结果，只有占位成功才执行业务。
        IdempotencyRecord record = redis.begin(userId, key, fingerprint, owner);
        if ("SUCCESS".equals(record.getState())) {
            context.setExecutionType("REPLAY");
            context.setTargetId(record.getOrderId());
            return Result.ok(record.getOrderId());
        }
        if ("PROCESSING".equals(record.getState())) {
            throw new BookingControlException("IN_PROGRESS", "预约正在处理中，请稍后重试或查看我的预约");
        }
        if ("CONFLICT".equals(record.getState())) {
            throw new BookingControlException("CONFLICT", "同一请求标识不能提交不同预约内容");
        }
        if (!"ACQUIRED".equals(record.getState())) {
            throw new BookingControlException("INVALID_STATE", "预约服务暂不可用，请稍后重试");
        }

        Object response;
        try {
            response = point.proceed();
        } catch (Throwable failure) {
            // 3.明确未开始或已回滚才释放；提交结果未知时不立即放行重做。
            String transaction = context.getTransactionState();
            if (failure instanceof Exception
                    && !"NONE".equals(transaction)
                    && !"ROLLED_BACK".equals(transaction)) {
                log.warn(
                        "预约事务结果未确认 requestId={} exception={}",
                        context.getRequestId(),
                        failure.getClass().getSimpleName());
                BookingControlException uncertain =
                        new BookingControlException(
                                "RESULT_UNCONFIRMED", "预约结果尚未确认，请先查看我的预约，勿重复创建");
                uncertain.initCause(failure);
                throw uncertain;
            }
            releaseIfSafe(context, userId, key, fingerprint, owner);
            throw failure;
        }

        // 4.Service 事务已提交后保存三天结果；缓存异常不能改变已成功的订单。
        if (response instanceof Result
                && Boolean.TRUE.equals(((Result) response).getSuccess())
                && "COMMITTED".equals(context.getTransactionState())) {
            Object orderId = ((Result) response).getData();
            if (orderId instanceof String) {
                try {
                    boolean saved =
                            redis.complete(userId, key, fingerprint, owner, (String) orderId);
                    if (!saved) {
                        log.warn("预约幂等结果未确认 requestId={}", context.getRequestId());
                    }
                } catch (RuntimeException failure) {
                    log.warn(
                            "预约已提交但幂等缓存写入失败 requestId={} exception={}",
                            context.getRequestId(),
                            failure.getClass().getSimpleName());
                }
            }
        } else {
            releaseIfSafe(context, userId, key, fingerprint, owner);
        }
        return response;
    }

    private void releaseIfSafe(
            BookingOperationContext.State context,
            Long userId,
            String key,
            String fingerprint,
            String owner) {
        if (!"NONE".equals(context.getTransactionState())
                && !"ROLLED_BACK".equals(context.getTransactionState())) {
            return;
        }
        try {
            redis.release(userId, key, fingerprint, owner);
        } catch (RuntimeException failure) {
            log.warn(
                    "预约幂等占位清理失败 requestId={} exception={}",
                    context.getRequestId(),
                    failure.getClass().getSimpleName());
        }
    }
}
