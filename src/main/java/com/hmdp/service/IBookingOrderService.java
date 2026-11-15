package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.BookingOrder;

public interface IBookingOrderService extends IService<BookingOrder> {
    Result createOrder(BookingRequestDTO request);

    Result listOrders(String status, Integer current, boolean admin);

    Result orderDetail(String id);

    Result cancelOrder(String id);

    Result verifyOrder(String id, String verifyCode);
}
