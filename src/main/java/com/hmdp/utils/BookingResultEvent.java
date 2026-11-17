package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;

/** 结果事件只携带不可变身份，业务资料始终从持久请求读取。 */
public final class BookingResultEvent {

    private BookingResultEvent() {}

    public static byte[] encode(String requestId) {
        if (!validId(requestId)) {
            throw new IllegalArgumentException("预约结果请求编号无效");
        }
        JSONObject value = new JSONObject();
        value.set("schemaVersion", 1);
        value.set("requestId", requestId);
        value.set("eventId", requestId + ":result:v1");
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > 256) {
            throw new IllegalArgumentException("预约结果消息长度无效");
        }
        try {
            JSONObject value = JSONUtil.parseObj(new String(body, StandardCharsets.UTF_8));
            String requestId = value.getStr("requestId");
            Object version = value.get("schemaVersion");
            if (value.size() != 3
                    || !(version instanceof Number)
                    || !"1".equals(version.toString())
                    || !validId(requestId)
                    || !(requestId + ":result:v1").equals(value.getStr("eventId"))) {
                throw new IllegalArgumentException("预约结果消息身份或版本无效");
            }
            return requestId;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("预约结果消息无法识别", exception);
        }
    }

    private static boolean validId(String requestId) {
        return requestId != null && requestId.matches("[0-9a-fA-F]{32}");
    }
}
