package com.hmdp.utils;

import com.hmdp.dto.BookingCompanionDTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 预约规则集中在服务端执行，浏览器仅用于展示。 */
public final class BookingRules {
    private BookingRules() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    public static Long currentUserId() {
        if (UserHolder.getUser() == null) {
            throw new BookingException("请先登录");
        }
        return UserHolder.getUser().getId();
    }

    public static String required(String value, String label, int max) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
            throw new BookingException(label + "不能为空且长度不能超过" + max + "个字符");
        }
        return value.trim();
    }

    public static void validateSlot(
            LocalDate date, LocalTime start, LocalTime end, LocalDateTime now) {
        if (date == null || start == null || end == null || !start.isBefore(end)) {
            throw new BookingException("请选择有效的日期和时段，同一时段不能跨天");
        }
        if (date.isBefore(now.toLocalDate()) || date.isAfter(now.toLocalDate().plusDays(6))) {
            throw new BookingException("仅可预约今天起七天内的场次");
        }
        if (!date.atTime(start).isAfter(now)) {
            throw new BookingException("该时段已经开始，请选择其他时段");
        }
    }

    public static void validateCompanions(
            List<BookingCompanionDTO> people, String studentNo, int maxPeople, String mode) {
        if (people == null) {
            throw new BookingException("同行人信息格式错误");
        }
        if (("SESSION".equals(mode) && !people.isEmpty()) || people.size() + 1 > maxPeople) {
            throw new BookingException("同行人数超过场地限制");
        }
        Set<String> seen = new HashSet<>();
        seen.add(studentNo);
        for (BookingCompanionDTO person : people) {
            if (person == null) {
                throw new BookingException("请填写完整的同行人信息");
            }
            person.setName(required(person.getName(), "同行人姓名", 32));
            person.setStudentNo(required(person.getStudentNo(), "同行人学号/工号", 32));
            if (!seen.add(person.getStudentNo())) {
                throw new BookingException("同行人不能与本人或其他同行人重复");
            }
        }
    }

    public static boolean canCancel(
            String status, LocalDateTime start, int advanceMinutes, LocalDateTime now) {
        return "RESERVED".equals(status)
                && start.isAfter(now)
                && !now.isAfter(start.minusMinutes(advanceMinutes));
    }

    public static boolean canVerify(
            String status, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return "RESERVED".equals(status)
                && !now.isBefore(start.minusMinutes(30))
                && now.isBefore(end);
    }
}
