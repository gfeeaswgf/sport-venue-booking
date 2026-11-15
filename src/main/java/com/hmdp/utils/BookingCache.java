package com.hmdp.utils;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.BookingRedisProperties;
import com.hmdp.entity.VenueSlot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.hmdp.utils.RedisConstants.*;

/** 公共查询缓存：读时回源加载，数据库提交后删除相关缓存。 */
@Slf4j
@Component
public class BookingCache {
    private final StringRedisTemplate redis;
    private final BookingRedisProperties properties;

    public BookingCache(StringRedisTemplate redis, BookingRedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public <T> T get(String name, Class<T> type, Supplier<T> loader) {
        // 1.先读取 Redis，命中后直接返回缓存内容
        String key = catalogKey(name);
        // 读取缓存只负责查询，Redis 异常时仍允许回源数据库
        String cached = read(key);
        if (cached != null) {
            try {
                return "null".equals(cached) ? null : JSONUtil.toBean(cached, type);
            } catch (RuntimeException e) {
                log.warn("预约缓存解析失败，回源数据库: {}", key);
            }
        }

        // 2.缓存未命中或解析失败时查询数据库，再按配置写入缓存
        T value = loader.get();
        write(
                key,
                value == null ? "null" : JSONUtil.toJsonStr(value),
                value == null ? 30 : catalogTtl());
        return value;
    }

    public <T> List<T> getList(String name, Class<T> type, Supplier<List<T>> loader) {
        return loadList(catalogKey(name), type, loader, catalogTtl());
    }

    public List<VenueSlot> getSlots(
            Long courtId, LocalDate date, Supplier<List<VenueSlot>> loader) {
        String key = properties.isEnabled() ? slotsKey(courtId, date) : null;
        return loadList(key, VenueSlot.class, loader, Math.max(1, properties.getSlotsTtlSeconds()));
    }

    private <T> List<T> loadList(String key, Class<T> type, Supplier<List<T>> loader, long ttl) {
        String cached = read(key);
        if (cached != null) {
            try {
                return JSONUtil.toList(JSONUtil.parseArray(cached), type);
            } catch (RuntimeException e) {
                log.warn("预约列表缓存解析失败，回源数据库: {}", key);
            }
        }
        // 未命中时加载列表，空结果使用短过期时间避免长期缓存空数据
        List<T> value = loader.get();
        write(
                key,
                JSONUtil.toJsonStr(value),
                value.isEmpty() ? (ttl == 0 ? 10 : Math.min(ttl, 10)) : ttl);
        return value;
    }

    private String catalogKey(String name) {
        if (!properties.isEnabled()) {
            return null;
        }
        // 搜索条件使用摘要；场馆详情和场地列表保留直观的 ID。
        if (name.startsWith("venues:")) {
            return key(BOOKING_CACHE_KEY + "venues:" + DigestUtil.md5Hex(name));
        }
        return key(BOOKING_CACHE_KEY + name);
    }

    private String slotsKey(Long courtId, LocalDate date) {
        return key(BOOKING_CACHE_KEY + "slots:" + courtId + ":" + date);
    }

    private String key(String name) {
        return properties.getNamespace() + name;
    }

    private long catalogTtl() {
        return Math.max(0, properties.getCatalogTtlSeconds());
    }

    private String read(String key) {
        if (key == null) {
            return null;
        }
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("预约缓存读取失败，回源数据库: {}", e.toString());
            return null;
        }
    }

    private void write(String key, String json, long ttl) {
        if (key == null) {
            return;
        }
        try {
            if (ttl == 0) {
                redis.opsForValue().set(key, json);
            } else {
                redis.opsForValue().set(key, json, ttl, TimeUnit.SECONDS);
            }
        } catch (RuntimeException e) {
            log.warn("预约缓存写入失败: {}", e.toString());
        }
    }

    /** 场馆/场地修改会影响列表、搜索和关联场次，提交后清理查询缓存。 */
    public void catalogChanged() {
        if (!properties.isEnabled()) {
            return;
        }
        // 数据库事务提交后再删除缓存，回滚时保留原缓存
        BookingTransactions.afterCommit(
                () -> {
                    String prefix = key(BOOKING_CACHE_KEY);
                    try {
                        // 列表存在不同搜索条件，SCAN 分批删除；只操作预约查询缓存前缀。
                        redis.execute(
                                (RedisCallback<Void>)
                                        connection -> {
                                            List<byte[]> batch = new ArrayList<>();
                                            ScanOptions options =
                                                    ScanOptions.scanOptions()
                                                            .match(prefix + "*")
                                                            .count(100)
                                                            .build();
                                            try (Cursor<byte[]> cursor = connection.scan(options)) {
                                                while (cursor.hasNext()) {
                                                    byte[] candidate = cursor.next();
                                                    if (!new String(
                                                                    candidate,
                                                                    StandardCharsets.UTF_8)
                                                            .startsWith(prefix)) {
                                                        continue;
                                                    }
                                                    batch.add(candidate);
                                                    if (batch.size() == 100) {
                                                        connection.del(
                                                                batch.toArray(new byte[0][]));
                                                        batch.clear();
                                                    }
                                                }
                                                if (!batch.isEmpty()) {
                                                    connection.del(batch.toArray(new byte[0][]));
                                                }
                                            }
                                            return null;
                                        });
                    } catch (RuntimeException e) {
                        log.error("场馆缓存删除失败，基础缓存默认不过期，需要重试维护操作或删除对应查询缓存: {}", e.toString());
                    }
                });
    }

    public void slotsChanged(Long courtId, LocalDate date) {
        if (!properties.isEnabled()) {
            return;
        }
        BookingTransactions.afterCommit(
                () -> {
                    try {
                        redis.delete(slotsKey(courtId, date));
                    } catch (RuntimeException e) {
                        log.warn("场次缓存删除失败，将等待 TTL 到期: {}", e.toString());
                    }
                });
    }
}
