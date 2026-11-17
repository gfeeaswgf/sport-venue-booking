package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VenueSlot;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface VenueSlotMapper extends BaseMapper<VenueSlot> {

    List<VenueSlot> findWindowForCourts(
            @Param("courtIds") List<Long> courtIds,
            @Param("firstDate") LocalDate firstDate,
            @Param("lastDate") LocalDate lastDate);

    int insertBatch(@Param("slots") List<VenueSlot> slots);

    @Select("SELECT * FROM tb_venue_slot WHERE id = #{id} FOR UPDATE")
    VenueSlot lockById(@Param("id") Long id);

    @Select(
            "SELECT id FROM tb_venue_slot WHERE court_id = #{courtId} AND booking_date = #{date}"
                    + " AND start_time < #{end} AND end_time > #{start} FOR UPDATE")
    List<Long> findOverlappingIds(
            @Param("courtId") Long courtId,
            @Param("date") LocalDate date,
            @Param("start") LocalTime start,
            @Param("end") LocalTime end);

    @Update(
            "UPDATE tb_venue_slot SET booked_count = booked_count + 1 WHERE id = #{id} AND enabled"
                    + " = 1 AND booked_count < capacity")
    int acquire(@Param("id") Long id);

    @Update(
            "UPDATE tb_venue_slot SET booked_count = booked_count - 1 WHERE id = #{id} AND"
                    + " booked_count > 0")
    int release(@Param("id") Long id);
}
