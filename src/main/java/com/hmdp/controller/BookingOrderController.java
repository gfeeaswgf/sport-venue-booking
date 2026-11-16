package com.hmdp.controller;

import com.hmdp.annotation.ApiTiming;
import com.hmdp.annotation.Idempotent;
import com.hmdp.annotation.OperationLog;
import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IAsyncBookingService;
import com.hmdp.service.IBookingOrderService;
import com.hmdp.utils.BookingOperationContext;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/booking/orders")
public class BookingOrderController {
    @Resource
    private IBookingOrderService bookingOrderService;

    @Resource
    private IAsyncBookingService asyncBookingService;

    @ApiTiming("受理预约请求")
    @OperationLog(value = "SUBMIT_BOOKING", targetType = "REQUEST")
    @RateLimit
    @Idempotent
    @PostMapping
    public Result create(@RequestBody BookingRequestDTO request) {
        BookingOperationContext.State context = BookingOperationContext.current();
        return Result.ok(
                asyncBookingService.accept(
                        request, context.getIdempotencyKey(), context.getRequestFingerprint()));
    }

    @GetMapping
    public Result list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer current) {
        return bookingOrderService.listOrders(status, current, false);
    }

    @GetMapping("/{id}")
    public Result detail(@PathVariable String id) {
        return bookingOrderService.orderDetail(id);
    }

    @PutMapping("/{id}/cancel")
    @OperationLog(value = "CANCEL_BOOKING", targetType = "ORDER", idArgument = 0)
    public Result cancel(@PathVariable String id) {
        return bookingOrderService.cancelOrder(id);
    }
}
