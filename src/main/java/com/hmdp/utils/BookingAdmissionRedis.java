package com.hmdp.utils;

import cn.hutool.json.JSONUtil;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.dto.BookingSubmissionDTO;
import com.hmdp.entity.BookingRequestTask;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 前置准入与结果缓存；库存最终正确性仍由消费者中的MySQL事务保证。 */
@Slf4j
@Component
public class BookingAdmissionRedis {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("booking-admission.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final BookingRedisProperties properties;
    private final BookingControlsProperties controls;

    public BookingAdmissionRedis(
            StringRedisTemplate redis,
            BookingRedisProperties properties,
            BookingControlsProperties controls) {
        this.redis = redis;
        this.properties = properties;
        this.controls = controls;
    }

    public void reserve(BookingRequestTask task, int remaining) {
        Long result;
        try {
            result =
                    redis.execute(
                            SCRIPT,
                            keys(task),
                            "reserve",
                            task.getId(),
                            String.valueOf(Math.max(0, remaining)),
                            String.valueOf(controls.getProcessingTtlSeconds()),
                            String.valueOf(
                                    Math.min(stockTtl(task), controls.getProcessingTtlSeconds())));
        } catch (RuntimeException failure) {
            // 脚本可能已经执行、仅回复丢失；尚未开始SQL事务，可以按本请求补偿。
            release(task);
            throw new BookingControlException("REDIS_UNAVAILABLE", "预约服务暂不可用，请稍后重试");
        }
        if (Long.valueOf(1).equals(result)) {
            throw new BookingException("该场次已约满或正在确认，请选择其他时段");
        }
        if (Long.valueOf(2).equals(result)) {
            throw new BookingException("已有预约请求正在处理，请稍后重试或查看我的预约");
        }
        if (!Long.valueOf(0).equals(result)) {
            throw new BookingException("预约服务暂不可用，请稍后重试");
        }
    }

    public void release(BookingRequestTask task) {
        update(task, "release");
    }

    public String markerOwner(BookingRequestTask task) {
        String marker = redis.opsForValue().get(keys(task).get(1));
        if (marker == null || marker.length() < 3) {
            return null;
        }
        return marker.substring(2);
    }

    public void confirm(BookingRequestTask task) {
        update(task, "confirm");
    }

    public int remaining(Long slotId, int databaseRemaining) {
        try {
            String value =
                    redis.opsForValue()
                            .get(properties.getNamespace() + "booking:async:stock:" + slotId);
            if (value != null) {
                return Math.max(0, Math.min(databaseRemaining, Integer.parseInt(value)));
            }
        } catch (RuntimeException ignored) {
            // 公共场次查询故障时回到SQL展示；提交仍重新进行准入和事务校验。
        }
        return databaseRemaining;
    }

    public BookingSubmissionDTO cachedResult(Long userId, String requestId) {
        try {
            String value = redis.opsForValue().get(resultKey(userId, requestId));
            return value == null ? null : JSONUtil.toBean(value, BookingSubmissionDTO.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void cacheResult(BookingRequestTask task) {
        if ("ACCEPTED".equals(task.getStatus()) || task.getCompleteTime() == null) {
            return;
        }
        long ttl =
                Duration.between(
                                BookingRules.now(),
                                task.getCompleteTime().plusSeconds(controls.getSuccessTtlSeconds()))
                        .getSeconds();
        if (ttl <= 0) {
            return;
        }
        try {
            // 按完成时间计算剩余期限，重复读取/消费不延长三天期限。
            redis.opsForValue()
                    .setIfAbsent(
                            resultKey(task.getUserId(), task.getId()),
                            JSONUtil.toJsonStr(BookingSubmissionDTO.from(task)),
                            ttl,
                            TimeUnit.SECONDS);
        } catch (RuntimeException failure) {
            log.warn(
                    "预约结果缓存未保存 requestId={} exception={}",
                    task.getId(),
                    failure.getClass().getSimpleName());
        }
    }

    private void update(BookingRequestTask task, String action) {
        try {
            redis.execute(
                    SCRIPT,
                    keys(task),
                    action,
                    task.getId(),
                    "0",
                    "0",
                    String.valueOf(stockTtl(task)));
        } catch (RuntimeException failure) {
            log.warn(
                    "预约准入状态同步失败 requestId={} action={} exception={}",
                    task.getId(),
                    action,
                    failure.getClass().getSimpleName());
        }
    }

    private List<String> keys(BookingRequestTask task) {
        return Arrays.asList(
                properties.getNamespace() + "booking:async:stock:" + task.getSlotId(),
                properties.getNamespace()
                        + "booking:async:user:"
                        + task.getBookingDate()
                        + ":"
                        + task.getUserId());
    }

    private long stockTtl(BookingRequestTask task) {
        return Math.max(
                60,
                Duration.between(
                                BookingRules.now(),
                                task.getBookingDate().plusDays(2).atStartOfDay())
                        .getSeconds());
    }

    private String resultKey(Long userId, String requestId) {
        return properties.getNamespace() + "booking:request:result:" + userId + ":" + requestId;
    }
}
