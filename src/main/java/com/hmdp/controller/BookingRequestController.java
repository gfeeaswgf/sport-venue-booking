package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IAsyncBookingService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/booking/requests")
public class BookingRequestController {
    @Resource
    private IAsyncBookingService asyncBookingService;

    @GetMapping("/{id}")
    public Result result(@PathVariable String id) {
        return Result.ok(asyncBookingService.result(id));
    }

    @GetMapping
    public Result recent() {
        return Result.ok(asyncBookingService.recent());
    }
}
