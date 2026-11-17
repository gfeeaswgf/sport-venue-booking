package com.hmdp.utils;

import cn.hutool.json.JSONUtil;

import com.hmdp.config.BookingControlsProperties;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.dto.OperationLogDTO;
import com.hmdp.entity.BookingRequestTask;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

/** 幂等效果与有效期限在同一 Lua 执行；异常必须交给消费组重试。 */
@Component
public class BookingResultRedis {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SECOND =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DefaultRedisScript<Long> AUDIT = script("booking-result-audit.lua");
    private static final DefaultRedisScript<Long> STATS = script("booking-result-stats.lua");

    private final StringRedisTemplate redis;
    private final BookingRedisProperties properties;
    private final BookingControlsProperties controls;

    public BookingResultRedis(
            StringRedisTemplate redis,
            BookingRedisProperties properties,
            BookingControlsProperties controls) {
        this.redis = redis;
        this.properties = properties;
        this.controls = controls;
    }

    public boolean writeAudit(BookingRequestTask task) {
        // 1. 内容来自持久快照；JSON 字段顺序和旧版纳秒时间不作为消息身份。
        OperationLogDTO record = new OperationLogDTO();
        record.setRequestId(task.getId());
        record.setUserId(task.getUserId());
        record.setOperation("CREATE_BOOKING");
        record.setTargetType("ORDER");
        record.setTargetId(task.getId());
        record.setOutcome("SUCCEEDED".equals(task.getStatus()) ? "SUCCESS" : "REJECTED");
        record.setExecutionType("EXECUTED");
        record.setCreateTime(task.getCompleteTime().toString());
        LocalDateTime second = task.getCompleteTime().truncatedTo(ChronoUnit.SECONDS);
        Long result =
                execute(
                        AUDIT,
                        properties.getNamespace() + "booking:audit:" + task.getId(),
                        JSONUtil.toJsonStr(record),
                        String.valueOf(
                                epochMillis(
                                        task.getCompleteTime()
                                                .plusSeconds(controls.getAuditTtlSeconds()))),
                        second.format(SECOND),
                        second.minusSeconds(1).format(SECOND),
                        second.plusSeconds(1).format(SECOND),
                        String.valueOf(task.getCompleteTime().getNano()),
                        String.valueOf(task.getUserId()));
        return applied(result);
    }

    public boolean writeStatistics(BookingRequestTask task, long count) {
        Long result =
                execute(
                        STATS,
                        properties.getNamespace()
                                + "booking:stats:created:"
                                + task.getBookingDate(),
                        String.valueOf(task.getVenueId()),
                        String.valueOf(count),
                        String.valueOf(
                                epochMillis(task.getBookingDate().plusDays(3).atStartOfDay())));
        return applied(result);
    }

    private boolean applied(Long result) {
        if (Long.valueOf(0).equals(result)) {
            return false;
        }
        if (Long.valueOf(1).equals(result) || Long.valueOf(2).equals(result)) {
            return true;
        }
        throw new IllegalStateException("预约结果 Redis 效果未确认或与已有审计冲突");
    }

    private Long execute(DefaultRedisScript<Long> script, String key, String... arguments) {
        Long result = redis.execute(script, Collections.singletonList(key), (Object[]) arguments);
        if (result == null || result < 0) {
            throw new IllegalStateException("预约结果Redis效果未确认或存在冲突");
        }
        return result;
    }

    private static long epochMillis(LocalDateTime time) {
        return time.atZone(ZONE).toInstant().toEpochMilli();
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
