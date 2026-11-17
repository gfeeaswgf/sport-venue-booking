package com.hmdp.service.impl;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingOrderMapper;
import com.hmdp.mapper.BookingResultMapper;
import com.hmdp.service.IBookingResultService;
import com.hmdp.utils.BookingResultRedis;
import com.hmdp.utils.BookingResultTask;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 每组只做自己的幂等效果和短确认，不开启跨Redis/SQL的大事务。 */
@Service
public class BookingResultServiceImpl implements IBookingResultService {

    private final BookingResultMapper requests;
    private final BookingOrderMapper orders;
    private final BookingResultRedis redis;
    private final BookingControlsProperties controls;

    public BookingResultServiceImpl(
            BookingResultMapper requests,
            BookingOrderMapper orders,
            BookingResultRedis redis,
            BookingControlsProperties controls) {
        this.requests = requests;
        this.orders = orders;
        this.redis = redis;
        this.controls = controls;
    }

    @Override
    public void handle(String requestId, BookingResultTask type) {
        // 1. 普通主键读，不在持有请求行锁时调用Redis或查询统计。
        BookingRequestTask task = requests.snapshot(requestId);
        if (task == null || !terminal(task)) {
            throw new IllegalStateException("预约结果尚无持久终态");
        }
        if (settled(state(task, type))) {
            return;
        }
        if (!"PENDING".equals(state(task, type))) {
            throw new IllegalStateException("预约结果任务状态无效");
        }
        LocalDateTime deadline = deadlineUtc(task, type);
        if (!LocalDateTime.now(ZoneOffset.UTC).isBefore(deadline)) {
            confirm(task, type, "EXPIRED", deadline);
            return;
        }

        // 2. 效果已写入但SQL确认失败时，下次继续幂等执行，不回退业务订单。
        boolean applied;
        if (type == BookingResultTask.AUDIT) {
            applied = redis.writeAudit(task);
        } else {
            long count = orders.countCreated(task.getVenueId(), task.getBookingDate());
            applied = redis.writeStatistics(task, count);
        }
        confirm(task, type, applied ? "DONE" : "EXPIRED", deadline);
    }

    @Override
    public boolean expireTasks(BookingRequestTask task) {
        // 3. 消费组离线也能结算已过期任务，发布器不执行审计或COUNT。
        for (BookingResultTask type : BookingResultTask.values()) {
            if ("PENDING".equals(state(task, type))) {
                LocalDateTime deadline = deadlineUtc(task, type);
                if (!LocalDateTime.now(ZoneOffset.UTC).isBefore(deadline)) {
                    confirm(task, type, "EXPIRED", deadline);
                    setState(task, type, "EXPIRED");
                }
            }
        }
        return settled(task.getAuditState()) && settled(task.getStatsState());
    }

    private void confirm(
            BookingRequestTask task,
            BookingResultTask type,
            String desired,
            LocalDateTime deadline) {
        int changed = finish(task.getId(), type, desired, deadline);
        if (changed == 0 && "DONE".equals(desired)) {
            // Redis成功后确认语句跨过截止，数据库仍以自己的执行时钟裁决。
            changed = finish(task.getId(), type, "EXPIRED", deadline);
        }
        if (changed == 1) {
            return;
        }
        // 4. 另一个重复消费者可能已确认；只有持久状态已结算才能ACK。
        BookingRequestTask latest = requests.snapshot(task.getId());
        if (latest == null || !settled(state(latest, type))) {
            throw new IllegalStateException("预约结果任务确认尚未完成");
        }
    }

    private int finish(String id, BookingResultTask type, String state, LocalDateTime deadline) {
        if (type == BookingResultTask.AUDIT) {
            return requests.finishAudit(id, state, deadline);
        }
        return requests.finishStats(id, state, deadline);
    }

    private LocalDateTime deadlineUtc(BookingRequestTask task, BookingResultTask type) {
        LocalDateTime deadline;
        if (type == BookingResultTask.AUDIT) {
            deadline = task.getCompleteTime().plusSeconds(controls.getAuditTtlSeconds());
        } else {
            deadline = task.getBookingDate().plusDays(3).atStartOfDay();
        }
        return LocalDateTime.ofInstant(
                deadline.atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneOffset.UTC);
    }

    private static String state(BookingRequestTask task, BookingResultTask type) {
        return type == BookingResultTask.AUDIT ? task.getAuditState() : task.getStatsState();
    }

    private static void setState(BookingRequestTask task, BookingResultTask type, String state) {
        if (type == BookingResultTask.AUDIT) {
            task.setAuditState(state);
        } else {
            task.setStatsState(state);
        }
    }

    private static boolean settled(String state) {
        return "DONE".equals(state) || "SKIPPED".equals(state) || "EXPIRED".equals(state);
    }

    private static boolean terminal(BookingRequestTask task) {
        return "SUCCEEDED".equals(task.getStatus()) || "FAILED".equals(task.getStatus());
    }
}
