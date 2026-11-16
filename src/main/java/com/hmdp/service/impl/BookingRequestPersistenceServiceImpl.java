package com.hmdp.service.impl;

import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.service.IBookingRequestPersistenceService;
import com.hmdp.utils.BookingAdmissionRedis;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingOperationContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;

@Service
public class BookingRequestPersistenceServiceImpl implements IBookingRequestPersistenceService {
    @Resource
    private BookingRequestMapper requests;

    @Resource
    private BookingAdmissionRedis admission;

    @Override
    @Transactional
    public void save(BookingRequestTask task) {
        BookingOperationContext.trackTransaction();
        // 只在已知回滚时释放。提交结果未知则保留，由同Key重试查询数据库确认。
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            admission.release(task);
                        }
                    }
                });
        if (requests.insert(task) != 1) {
            throw new BookingException("预约请求保存失败，请重试");
        }
    }
}
