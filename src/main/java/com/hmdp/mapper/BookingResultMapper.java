package com.hmdp.mapper;

import com.hmdp.entity.BookingRequestTask;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 结果发送和任务确认均为短 SQL，不在 Redis 调用期间持有请求行锁。 */
public interface BookingResultMapper {

    BookingRequestTask snapshot(@Param("id") String id);

    List<BookingRequestTask> findDue(
            @Param("status") String status,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    int claim(
            @Param("id") String id,
            @Param("token") String token,
            @Param("now") LocalDateTime now,
            @Param("next") LocalDateTime next);

    int published(
            @Param("id") String id, @Param("token") String token, @Param("now") LocalDateTime now);

    int finishAudit(
            @Param("id") String id,
            @Param("state") String state,
            @Param("deadlineUtc") LocalDateTime deadlineUtc);

    int finishStats(
            @Param("id") String id,
            @Param("state") String state,
            @Param("deadlineUtc") LocalDateTime deadlineUtc);
}
