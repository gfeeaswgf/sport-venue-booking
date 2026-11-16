package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.dto.OperationLogDTO;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingOrderMapper;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.service.IBookingCompletionService;
import com.hmdp.utils.BookingAdmissionRedis;
import com.hmdp.utils.BookingRules;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

/** 核心事务结束后的可重试任务；失败不会把已经成功的订单改成失败。 */
@Service
public class BookingCompletionServiceImpl implements IBookingCompletionService {
    private static final DefaultRedisScript<Long> STATS = new DefaultRedisScript<>();

    static {
        STATS.setLocation(new ClassPathResource("booking-created-stat.lua"));
        STATS.setResultType(Long.class);
    }

    @Resource
    private BookingRequestMapper requests;

    @Resource
    private BookingOrderMapper orders;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private BookingControlsProperties controls;

    @Resource
    private BookingRedisProperties cache;

    @Resource
    private BookingAdmissionRedis admission;

    @Override
    public void record(BookingRequestTask task) {
        if (task == null || "ACCEPTED".equals(task.getStatus())) {
            return;
        }
        // 1.结果缓存只是加速，真正状态一直由请求表保留。
        admission.cacheResult(task);

        // 2.最终审计使用固定Key及剩余TTL，重复投递不能增加记录或续期。
        long auditTtl =
                Duration.between(
                                BookingRules.now(),
                                task.getCompleteTime().plusSeconds(controls.getAuditTtlSeconds()))
                        .getSeconds();
        if (auditTtl > 0) {
            OperationLogDTO record = new OperationLogDTO();
            record.setRequestId(task.getId());
            record.setUserId(task.getUserId());
            record.setOperation("CREATE_BOOKING");
            record.setTargetType("ORDER");
            record.setTargetId(task.getId());
            record.setOutcome("SUCCEEDED".equals(task.getStatus()) ? "SUCCESS" : "REJECTED");
            record.setExecutionType("EXECUTED");
            record.setCreateTime(task.getCompleteTime().toString());
            // 消费补记不能恢复原方法的nanoTime，不伪造durationMs。
            redis.opsForValue()
                    .setIfAbsent(
                            cache.getNamespace() + "booking:audit:" + task.getId(),
                            JSONUtil.toJsonStr(record),
                            auditTtl,
                            TimeUnit.SECONDS);
        }

        // 3.历史创建次数直接由订单COUNT恢复，重复消费不直接INCR。
        if ("SUCCEEDED".equals(task.getStatus())) {
            long ttl =
                    Duration.between(
                                    BookingRules.now(),
                                    task.getBookingDate().plusDays(3).atStartOfDay())
                            .getSeconds();
            if (ttl > 0) {
                long count = orders.countCreated(task.getVenueId(), task.getBookingDate());
                redis.execute(
                        STATS,
                        Collections.singletonList(
                                cache.getNamespace()
                                        + "booking:stats:created:"
                                        + task.getBookingDate()),
                        String.valueOf(task.getVenueId()),
                        String.valueOf(count),
                        String.valueOf(ttl));
            }
        }

        // 4.完成后移出待办扫描。若这里或前面失败，30秒后还会补偿，不丢终态后处理。
        requests.completeDelivery(task.getId());
    }
}
