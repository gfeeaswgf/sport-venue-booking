package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.utils.BookingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(DuplicateKeyException.class)
    public Result handleDuplicateKey(DuplicateKeyException e) {
        return Result.fail("记录重复：每天仅可保留一笔预约，请刷新后检查");
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public Result handleInvalidInput(Exception e) {
        return Result.fail("请求参数格式错误，请检查后重试");
    }

    @ExceptionHandler(BookingException.class)
    public Result handleBookingException(BookingException e) {
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
