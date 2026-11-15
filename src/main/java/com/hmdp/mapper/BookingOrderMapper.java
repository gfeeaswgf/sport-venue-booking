package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.BookingOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BookingOrderMapper extends BaseMapper<BookingOrder> {
    @Select("SELECT * FROM tb_booking_order WHERE id = #{id} FOR UPDATE")
    BookingOrder lockById(@Param("id") String id);

    @Update(
            "UPDATE tb_booking_order SET status = 'CANCELLED', active_date = NULL, cancel_time = #{now} WHERE id = #{id} AND user_id = #{userId} AND status = 'RESERVED'")
    int cancelAt(
            @Param("id") String id,
            @Param("userId") Long userId,
            @Param("now") java.time.LocalDateTime now);

    default int cancel(String id, Long userId) {
        return cancelAt(id, userId, com.hmdp.utils.BookingRules.now());
    }

    @Update(
            "UPDATE tb_booking_order SET status = 'COMPLETED', verify_time = #{now} WHERE id = #{id} AND status = 'RESERVED'")
    int verify(@Param("id") String id, @Param("now") java.time.LocalDateTime now);

    @Select(
            "SELECT COUNT(*) FROM tb_booking_order WHERE venue_id = #{id} AND status = 'RESERVED' AND TIMESTAMP(booking_date, end_time) > #{now}")
    int futureForVenue(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    @Select(
            "SELECT COUNT(*) FROM tb_booking_order WHERE court_id = #{id} AND status = 'RESERVED' AND TIMESTAMP(booking_date, end_time) > #{now}")
    int futureForCourt(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);
}
