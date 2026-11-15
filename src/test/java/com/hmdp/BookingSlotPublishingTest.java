package com.hmdp;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;
import com.hmdp.mapper.VenueCourtMapper;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.mapper.VenueSlotMapper;
import com.hmdp.service.impl.VenueServiceImpl;
import com.hmdp.utils.BookingAdminAccess;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRules;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingSlotPublishingTest {
    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void overlapQueryUsesCurrentReadAndBindsTimeParameters() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(VenueSlotMapper.class);
        Map<String, Object> params = new HashMap<>();
        params.put("courtId", 1101L);
        params.put("date", LocalDate.of(2026, 11, 15));
        params.put("start", LocalTime.of(8, 30));
        params.put("end", LocalTime.of(9, 30));
        org.apache.ibatis.mapping.BoundSql sql =
                configuration
                        .getMappedStatement(VenueSlotMapper.class.getName() + ".findOverlappingIds")
                        .getBoundSql(params);
        assertTrue(sql.getSql().trim().endsWith("FOR UPDATE"));
        assertEquals(
                Arrays.asList("courtId", "date", "end", "start"),
                sql.getParameterMappings().stream()
                        .map(org.apache.ibatis.mapping.ParameterMapping::getProperty)
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void rejectsNewlyCommittedOverlapEvenWhenEarlierSnapshotWasEmpty() {
        VenueServiceImpl service = new VenueServiceImpl();
        VenueMapper venues = mock(VenueMapper.class);
        VenueCourtMapper courts = mock(VenueCourtMapper.class);
        // 模拟旧快照未看到时段、当前读已看到另一发布事务提交的时段。
        VenueSlotMapper slots =
                mock(
                        VenueSlotMapper.class,
                        call -> {
                            if ("selectCount".equals(call.getMethod().getName())) {
                                return 0;
                            }
                            if ("findOverlappingIds".equals(call.getMethod().getName())) {
                                return Collections.singletonList(900L);
                            }
                            if ("insert".equals(call.getMethod().getName())) {
                                return 1;
                            }
                            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                        });
        BookingAdminAccess admin = new BookingAdminAccess();
        ReflectionTestUtils.setField(admin, "adminUserIds", "7");
        ReflectionTestUtils.setField(service, "baseMapper", venues);
        ReflectionTestUtils.setField(service, "courtMapper", courts);
        ReflectionTestUtils.setField(service, "slotMapper", slots);
        ReflectionTestUtils.setField(service, "bookingAdminAccess", admin);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        Venue venue = new Venue().setId(1001L).setEnabled(true);
        VenueCourt court =
                new VenueCourt().setId(1101L).setVenueId(1001L).setEnabled(true).setCapacity(1);
        when(venues.lockById(1001L)).thenReturn(venue);
        when(courts.selectById(1101L)).thenReturn(court);
        when(courts.lockById(1101L)).thenReturn(court);
        VenueSlot request =
                new VenueSlot()
                        .setCourtId(1101L)
                        .setBookingDate(BookingRules.now().toLocalDate().plusDays(1))
                        .setStartTime(LocalTime.of(8, 30))
                        .setEndTime(LocalTime.of(9, 30));
        BookingException failure =
                assertThrows(BookingException.class, () -> service.publishSlot(request));
        assertTrue(failure.getMessage().contains("重叠"));
    }
}
