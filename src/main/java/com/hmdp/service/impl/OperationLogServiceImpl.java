package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.dto.OperationLogDTO;
import com.hmdp.service.IOperationLogService;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class OperationLogServiceImpl implements IOperationLogService {
    private final StringRedisTemplate redis;
    private final BookingControlsProperties controls;
    private final BookingRedisProperties cache;

    public OperationLogServiceImpl(
            StringRedisTemplate redis,
            BookingControlsProperties controls,
            BookingRedisProperties cache) {
        this.redis = redis;
        this.controls = controls;
        this.cache = cache;
    }

    @Override
    public void saveLog(OperationLogDTO record) {
        // 每条记录独立设置三天期限，写新记录不会延长旧记录的保存时间。
        String key = cache.getNamespace() + "booking:audit:" + record.getRequestId();
        redis.opsForValue()
                .set(
                        key,
                        JSONUtil.toJsonStr(record),
                        controls.getAuditTtlSeconds(),
                        TimeUnit.SECONDS);
    }
}
