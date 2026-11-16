package com.hmdp.service.impl;

import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.service.IBookingOrderService;
import com.hmdp.service.IBookingRequestWorkerService;
import com.hmdp.utils.BookingAdmissionRedis;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRules;
import com.hmdp.utils.BookingTransactions;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import javax.annotation.Resource;

@Service
public class BookingRequestWorkerServiceImpl implements IBookingRequestWorkerService {
    @Resource
    private BookingRequestMapper requests;

    @Resource
    private IBookingOrderService orders;

    @Resource
    private BookingAdmissionRedis admission;

    @Override
    @Transactional
    public BookingRequestTask process(String requestId) {
        // 1.锁住请求行；重复投递或进程重启后，先看持久化终态。
        BookingRequestTask task = requests.lockById(requestId);
        if (task == null || !"ACCEPTED".equals(task.getStatus())) {
            return task;
        }
        LocalDateTime now = BookingRules.now();
        if (!now.isBefore(task.getExpireTime())) {
            throw new BookingException("预约确认超时，请重新选择场次");
        }

        // 2.订单与请求成功状态在同一个事务提交；消息线程不读取UserHolder。
        orders.createAcceptedOrder(task);
        if (requests.succeed(task.getId(), now) != 1) {
            throw new IllegalStateException("预约请求状态更新失败");
        }
        task.setStatus("SUCCEEDED");
        task.setCompleteTime(now);
        BookingTransactions.afterCommit(() -> admission.confirm(task));
        return task;
    }

    @Override
    @Transactional
    public BookingRequestTask fail(String requestId, String reason) {
        // 关闭请求也锁同一行，不能和正在提交的消费者并发判定失败。
        BookingRequestTask task = requests.lockById(requestId);
        if (task == null || !"ACCEPTED".equals(task.getStatus())) {
            return task;
        }
        LocalDateTime now = BookingRules.now();
        if (requests.fail(task.getId(), reason, now) != 1) {
            throw new IllegalStateException("预约请求失败状态更新未完成");
        }
        task.setStatus("FAILED");
        task.setActiveDate(null);
        task.setFailureReason(reason);
        task.setCompleteTime(now);
        BookingTransactions.afterCommit(() -> admission.release(task));
        return task;
    }
}
