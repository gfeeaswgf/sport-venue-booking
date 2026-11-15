package com.hmdp.utils;

import cn.hutool.crypto.digest.DigestUtil;

import com.hmdp.dto.BookingCompanionDTO;
import com.hmdp.dto.BookingRequestDTO;

/** 按固定字段顺序生成摘要，避免服务层后续修改 DTO 影响幂等确认。 */
public final class BookingRequestFingerprint {
    private BookingRequestFingerprint() {}

    public static String of(BookingRequestDTO request) {
        if (request == null
                || request.getSlotId() == null
                || !Boolean.TRUE.equals(request.getAgreement())) {
            throw new BookingControlException("INVALID_REQUEST", "请选择时段并阅读同意预约须知");
        }
        if (request.getCompanions() == null || request.getCompanions().size() > 5) {
            throw new BookingControlException("INVALID_REQUEST", "同行人信息格式错误或人数超过限制");
        }

        // 长度前缀区分字段边界，不拼接成可能碰撞的简单分隔字符串。
        StringBuilder value = new StringBuilder();
        append(value, request.getSlotId().toString());
        append(value, "true");
        for (BookingCompanionDTO companion : request.getCompanions()) {
            if (companion == null) {
                throw new BookingControlException("INVALID_REQUEST", "请填写完整的同行人信息");
            }
            append(value, BookingRules.required(companion.getName(), "同行人姓名", 32));
            append(value, BookingRules.required(companion.getStudentNo(), "同行人学号/工号", 32));
        }
        return hash(value.toString());
    }

    public static String hash(String value) {
        return DigestUtil.sha256Hex(value);
    }

    private static void append(StringBuilder builder, String value) {
        builder.append(value.length()).append(':').append(value);
    }
}
