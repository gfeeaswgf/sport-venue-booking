package com.hmdp.aspect;

import com.hmdp.annotation.ApiTiming;
import com.hmdp.dto.Result;
import com.hmdp.utils.BookingException;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

/** 记录被注解方法的耗时，不改变返回值或异常，不记录请求参数。 */
@Slf4j
@Aspect
@Component
@Order(20)
public class ApiTimingAspect {
    @Around("@annotation(apiTiming)")
    public Object record(ProceedingJoinPoint point, ApiTiming apiTiming) throws Throwable {
        // 1.使用单调时钟记录开始时间，初始结果按异常处理
        long start = System.nanoTime();
        String outcome = "ERROR";
        String exception = "-";
        try {
            // 2.仅执行一次原方法，正常返回时区分成功与业务拒绝
            Object result = point.proceed();
            outcome =
                    result instanceof Result && Boolean.FALSE.equals(((Result) result).getSuccess())
                            ? "REJECTED"
                            : "SUCCESS";
            return result;
        } catch (Throwable failure) {
            // 3.保留原异常对象，区分业务异常和系统异常后继续向外抛出
            outcome =
                    failure instanceof BookingException || failure instanceof DuplicateKeyException
                            ? "REJECTED"
                            : "ERROR";
            exception = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            // 在读取日志上下文和格式化之前结束计时，不计入本条日志的输出时间。
            long elapsed = System.nanoTime() - start;

            // 4.读取安全的请求上下文，最后统一输出一条耗时日志
            String method = "-";
            String path = "-";
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
                method = request.getMethod();
                Object pattern =
                        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                // 只记录 MVC 匹配模板，不读取 URL 查询串、请求头或请求体。
                if (pattern instanceof String) {
                    path = (String) pattern;
                }
            }
            log.info(
                    "api={} method={} path={} outcome={} durationMs={} exception={}",
                    apiTiming.value(),
                    method,
                    path,
                    outcome,
                    String.format(Locale.ROOT, "%.3f", elapsed / 1_000_000.0),
                    exception);
        }
    }
}
