package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface IVenueService extends IService<Venue> {
    Result listVenues(String name, String category, boolean admin);

    Result venueDetail(Long id);

    Result listCourts(Long venueId, boolean admin);

    Result listSlots(Long courtId, LocalDate date, boolean admin);

    Result saveVenue(Venue venue);

    Result saveCourt(VenueCourt court);

    Result publishSlot(VenueSlot slot);

    Result setSlotEnabled(Long id, boolean enabled);

    /** 内部定时任务：补齐今天起七天内缺失的场次，不修改历史或已有记录。 */
    int ensureRollingSlots(Long venueId, LocalDateTime now);
}
