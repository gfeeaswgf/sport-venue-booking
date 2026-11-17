package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.BookingRequestTask;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface BookingRequestMapper extends BaseMapper<BookingRequestTask> {
    @Select("SELECT * FROM tb_booking_request WHERE id=#{id} FOR UPDATE")
    BookingRequestTask lockById(@Param("id") String id);

    @Select("SELECT * FROM tb_booking_request WHERE user_id=#{userId} AND idempotency_key=#{key}")
    BookingRequestTask byKey(@Param("userId") Long userId, @Param("key") String key);

    @Select(
            "SELECT * FROM tb_booking_request WHERE user_id=#{userId} AND status IN"
                    + " ('ACCEPTED','FAILED') ORDER BY create_time DESC LIMIT 20")
    List<BookingRequestTask> recent(@Param("userId") Long userId);

    @Select(
            "SELECT GREATEST(0,s.capacity-s.booked_count-(SELECT COUNT(*) FROM tb_booking_request r"
                    + " WHERE r.slot_id=s.id AND r.status='ACCEPTED')) FROM tb_venue_slot s WHERE"
                    + " s.id=#{slotId}")
    Integer remainingForAdmission(@Param("slotId") Long slotId);

    @Select(
            "SELECT id,status,expire_time,next_publish_time FROM tb_booking_request "
                    + "WHERE status='ACCEPTED' AND next_publish_time<=#{now} "
                    + "ORDER BY next_publish_time,id LIMIT #{limit}")
    List<BookingRequestTask> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update(
            "UPDATE tb_booking_request SET"
                    + " next_publish_time=#{next},publish_count=publish_count+1,update_time=#{now}"
                    + " WHERE id=#{id} AND status='ACCEPTED' AND next_publish_time<=#{now}")
    int claim(
            @Param("id") String id,
            @Param("now") LocalDateTime now,
            @Param("next") LocalDateTime next);

    @Update(
            "UPDATE tb_booking_request SET"
                    + " status='SUCCEEDED',complete_time=#{now},update_time=#{now},"
                    + " result_event_state='PENDING',audit_state='PENDING',stats_state='PENDING',"
                    + " next_publish_time=#{now},result_publish_token=NULL WHERE id=#{id} AND"
                    + " status='ACCEPTED'")
    int succeed(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update(
            "UPDATE tb_booking_request SET"
                + " status='FAILED',active_date=NULL,failure_reason=#{reason},"
                + " complete_time=#{now},update_time=#{now},result_event_state='PENDING',"
                + " audit_state='PENDING',stats_state='SKIPPED',next_publish_time=#{now},result_publish_token=NULL"
                + " WHERE id=#{id} AND status='ACCEPTED'")
    int fail(
            @Param("id") String id,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    @Update(
            "UPDATE tb_booking_request SET active_date=NULL,update_time=#{now} WHERE id=#{orderId}"
                    + " AND user_id=#{userId} AND status='SUCCEEDED'")
    int releaseDate(
            @Param("orderId") String orderId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now);
}
