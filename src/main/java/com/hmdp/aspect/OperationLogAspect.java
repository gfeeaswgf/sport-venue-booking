package com.hmdp.aspect;

import com.hmdp.annotation.OperationLog;
import com.hmdp.dto.OperationLogDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IOperationLogService;
import com.hmdp.utils.BookingControlException;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingOperationContext;
import com.hmdp.utils.BookingRules;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@Aspect
@Component
@Order(10)
public class OperationLogAspect {
    private final IOperationLogService logs;

    public OperationLogAspect(IOperationLogService logs) {
        this.logs = logs;
    }

    @Around("@annotation(operation)")
    public Object record(ProceedingJoinPoint point, OperationLog operation) throws Throwable {
        boolean ownsContext = BookingOperationContext.current() == null;
        if (ownsContext) {
            BookingOperationContext.open();
        }
        BookingOperationContext.State context = BookingOperationContext.current();
        OperationLogDTO record = new OperationLogDTO();
        long start = System.nanoTime();
        try {
            // 1.只读取允许记录的操作元数据，不序列化请求参数。
            record.setUserId(BookingRules.currentUserId());
            record.setOperation(operation.value());
            record.setTargetType(operation.targetType());
            if (operation.idArgument() >= 0) {
                record.setTargetId(String.valueOf(point.getArgs()[operation.idArgument()]));
            }
            Object result = point.proceed();
            boolean rejected =
                    result instanceof Result
                            && Boolean.FALSE.equals(((Result) result).getSuccess());
            record.setOutcome(rejected ? "REJECTED" : "SUCCESS");
            if (!rejected && record.getTargetId() == null && result instanceof Result) {
                Object id = ((Result) result).getData();
                if (id instanceof String || id instanceof Long) {
                    record.setTargetId(String.valueOf(id));
                }
            }
            return result;
        } catch (Throwable failure) {
            // 2.已提交后仍抛异常，不能将它写成“数据库已回滚”。
            String transaction = context.getTransactionState();
            if ("COMMITTED".equals(transaction)
                    || "ACTIVE".equals(transaction)
                    || "UNKNOWN".equals(transaction)) {
                record.setOutcome("UNKNOWN");
            } else if (failure instanceof BookingException
                    || failure instanceof DuplicateKeyException) {
                record.setOutcome("REJECTED");
            } else {
                record.setOutcome("ERROR");
            }
            record.setExceptionType(failure.getClass().getSimpleName());
            if (failure instanceof BookingControlException) {
                String reason = ((BookingControlException) failure).getReason();
                record.setReasonCode(reason);
                if ("LIMITED".equals(reason)
                        || "IN_PROGRESS".equals(reason)
                        || "CONFLICT".equals(reason)) {
                    context.setExecutionType(reason);
                }
            }
            throw failure;
        } finally {
            // 3.先结束计时，再单独写审计；审计异常不覆盖业务返回值或异常。
            long elapsed = System.nanoTime() - start;
            try {
                record.setRequestId(context.getRequestId());
                record.setExecutionType(context.getExecutionType());
                record.setIdempotencyKeyHash(context.getIdempotencyKeyHash());
                record.setDurationMs(
                        BigDecimal.valueOf(elapsed, 6).setScale(3, RoundingMode.HALF_UP));
                record.setCreateTime(BookingRules.now().toString());
                fillHttpContext(record);
                logs.saveLog(record);
            } catch (RuntimeException failure) {
                log.warn(
                        "操作审计保存失败 requestId={} operation={} exception={}",
                        context.getRequestId(),
                        operation.value(),
                        failure.getClass().getSimpleName());
            } finally {
                if (ownsContext) {
                    BookingOperationContext.clear();
                }
            }
        }
    }

    private void fillHttpContext(OperationLogDTO record) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
            return;
        }
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        record.setHttpMethod(request.getMethod());
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String) {
            record.setPathPattern((String) pattern);
        }
    }
}
