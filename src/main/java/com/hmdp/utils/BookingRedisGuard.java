package com.hmdp.utils;

import static com.hmdp.utils.RedisConstants.*;

import com.hmdp.config.BookingRedisProperties;
import com.hmdp.entity.BookingOrder;
import com.hmdp.entity.VenueSlot;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** Redis 辅助预约校验，MySQL 条件更新及唯一约束仍为最终保障。 */
@Slf4j
@Component
public class BookingRedisGuard {
    private static final DefaultRedisScript<Long> RESERVE = script("booking-reserve.lua");
    private static final DefaultRedisScript<Long> COMPLETE = script("booking-complete.lua");
    private final StringRedisTemplate redis;
    private final BookingRedisProperties properties;

    public BookingRedisGuard(StringRedisTemplate redis, BookingRedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(Long.class);
        return script;
    }

    /** 调用者已在数据库锁内检查开放状态、日期、同行人、每日限制。 */
    public void reserve(VenueSlot slot, Long userId, String orderId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Redis 预占必须在预约事务内执行");
        }

        // 1.组合本场次名额 Key 和本用户当日预约 Key
        List<String> keys = keys(slot.getId(), slot.getBookingDate(), userId);
        long ttl = ttl(slot.getBookingDate());

        // 2.先登记事务提交确认和回滚补偿，覆盖脚本执行后网络超时的情况
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        complete(keys, "confirm", orderId, ttl);
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            complete(keys, "rollback", orderId, ttl);
                        }
                    }
                });

        // 3.执行 Lua 原子预占，再根据返回值给出可读的业务提示
        Long result;
        try {
            result =
                    redis.execute(
                            RESERVE,
                            keys,
                            String.valueOf(Math.max(0, slot.getCapacity() - slot.getBookedCount())),
                            orderId,
                            String.valueOf(Math.max(1, properties.getPendingSeconds())),
                            String.valueOf(ttl));
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        if (Long.valueOf(1).equals(result)) {
            throw new BookingException("该场次已约满，请选择其他时段");
        }
        if (Long.valueOf(2).equals(result)) {
            throw new BookingException("预约请求正在处理中，请稍后查看预约记录");
        }
        if (!Long.valueOf(0).equals(result)) {
            throw new BookingException("预约服务暂不可用，请稍后重试");
        }
    }

    public void cancelled(BookingOrder order) {
        if (!properties.isEnabled()) {
            return;
        }
        // 取消事务提交后，按订单归属清理 Redis 标记
        List<String> keys = keys(order.getSlotId(), order.getBookingDate(), order.getUserId());
        BookingTransactions.afterCommit(
                () -> complete(keys, "cancel", order.getId(), ttl(order.getBookingDate())));
    }

    private void complete(List<String> keys, String action, String orderId, long ttl) {
        try {
            redis.execute(COMPLETE, keys, action, orderId, String.valueOf(ttl));
        } catch (RuntimeException e) {
            // 事务已提交时不可向用户报告下单失败；pending 超时和锁内校准允许后续恢复。
            log.warn("预约 Redis 状态同步失败 action={} orderId={}: {}", action, orderId, e.toString());
        }
    }

    private List<String> keys(Long slotId, LocalDate date, Long userId) {
        return Arrays.asList(
                key(BOOKING_STOCK_KEY + slotId), key(BOOKING_USER_KEY + date + ":" + userId));
    }

    private String key(String suffix) {
        return properties.getNamespace() + suffix;
    }

    private long ttl(LocalDate date) {
        return Math.max(
                60,
                Duration.between(BookingRules.now(), date.plusDays(2).atStartOfDay()).getSeconds());
    }

    private BookingException unavailable(RuntimeException cause) {
        log.warn("预约 Redis 请求失败: {}", cause.toString());
        return new BookingException("预约服务暂不可用，请稍后重试");
    }
}
