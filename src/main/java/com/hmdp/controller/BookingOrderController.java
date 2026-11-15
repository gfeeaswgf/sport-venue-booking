package com.hmdp.controller;

import com.hmdp.annotation.ApiTiming;
import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IBookingOrderService;
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

    @ApiTiming("创建预约")
    @PostMapping
    public Result create(@RequestBody BookingRequestDTO request) {
        return bookingOrderService.createOrder(request);
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
    public Result cancel(@PathVariable String id) {
        return bookingOrderService.cancelOrder(id);
    }
}
