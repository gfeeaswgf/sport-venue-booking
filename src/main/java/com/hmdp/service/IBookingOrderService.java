package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.BookingOrder;
import com.hmdp.entity.BookingRequestTask;

public interface IBookingOrderService extends IService<BookingOrder> {
    BookingOrder createAcceptedOrder(BookingRequestTask request);

    Result listOrders(String status, Integer current, boolean admin);

    Result orderDetail(String id);

    Result cancelOrder(String id);

    Result verifyOrder(String id, String verifyCode);
}
