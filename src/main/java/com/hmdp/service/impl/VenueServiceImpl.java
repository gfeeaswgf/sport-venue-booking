package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;
import com.hmdp.mapper.BookingOrderMapper;
import com.hmdp.mapper.VenueCourtMapper;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.mapper.VenueSlotMapper;
import com.hmdp.service.IVenueService;
import com.hmdp.utils.BookingAdminAccess;
import com.hmdp.utils.BookingCache;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Resource;

@Service
public class VenueServiceImpl extends ServiceImpl<VenueMapper, Venue> implements IVenueService {
    @Resource
    private VenueCourtMapper courtMapper;

    @Resource
    private VenueSlotMapper slotMapper;

    @Resource
    private BookingOrderMapper orderMapper;

    @Resource
    private BookingAdminAccess bookingAdminAccess;

    @Resource
    private BookingCache bookingCache;

    private void requireAdmin() {
        if (!bookingAdminAccess.isAdmin(BookingRules.currentUserId())) {
            throw new BookingException("无管理权限");
        }
    }

    @Override
    @Transactional
    public int ensureRollingSlots(Long venueId, LocalDateTime now) {
        // 1.校验输入并锁定场馆，与管理和预约操作保持相同锁顺序
        if (venueId == null || now == null) {
            throw new IllegalArgumentException("场馆和当前时间不能为空");
        }
        // 与预约、管理维护共用场馆锁，避免同时发布重叠场次。
        Venue venue = baseMapper.lockById(venueId);
        if (venue == null || !Boolean.TRUE.equals(venue.getEnabled())) {
            return 0;
        }

        // 2.确定今天起七天的日期窗口，读取已开放场地
        LocalDate firstDate = now.toLocalDate();
        LocalDate lastDate = firstDate.plusDays(6);
        List<VenueCourt> courts =
                courtMapper.selectList(
                        new QueryWrapper<VenueCourt>()
                                .eq("venue_id", venueId)
                                .eq("enabled", true)
                                .orderByAsc("id"));

        // 3.按场地和默认时间模板补齐缺失场次，保留已有场次状态
        int inserted = 0;
        for (VenueCourt court : courts) {
            if (!Boolean.TRUE.equals(court.getEnabled())) {
                continue;
            }
            if (court.getCapacity() == null
                    || court.getCapacity() < 1
                    || court.getCapacity() > 1000) {
                throw new BookingException("场地容量无效，无法自动发布场次");
            }
            List<LocalTime[]> times = defaultSlotTimes(court.getMode());
            Set<LocalDate> changedDates = new HashSet<>();
            List<VenueSlot> existing =
                    new ArrayList<>(
                            slotMapper.selectList(
                                    new QueryWrapper<VenueSlot>()
                                            .eq("court_id", court.getId())
                                            .ge("booking_date", firstDate)
                                            .le("booking_date", lastDate)));
            for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
                for (LocalTime[] time : times) {
                    if (!date.atTime(time[0]).isAfter(now)) {
                        continue;
                    }
                    boolean overlaps = false;
                    for (VenueSlot old : existing) {
                        if (date.equals(old.getBookingDate())
                                && old.getStartTime().isBefore(time[1])
                                && old.getEndTime().isAfter(time[0])) {
                            overlaps = true;
                            break;
                        }
                    }
                    // 已关闭、已约满的场次也算存在，绝不恢复或覆盖。
                    if (overlaps) {
                        continue;
                    }
                    VenueSlot slot =
                            new VenueSlot()
                                    .setCourtId(court.getId())
                                    .setBookingDate(date)
                                    .setStartTime(time[0])
                                    .setEndTime(time[1])
                                    .setCapacity(court.getCapacity())
                                    .setBookedCount(0)
                                    .setEnabled(true)
                                    .setCreateTime(now);
                    if (slotMapper.insert(slot) != 1) {
                        throw new BookingException("自动发布场次失败");
                    }
                    existing.add(slot);
                    inserted++;
                    changedDates.add(date);
                }
            }

            // 4.记录发生新增的日期，事务提交后删除对应场次缓存
            for (LocalDate date : changedDates) {
                bookingCache.slotsChanged(court.getId(), date);
            }
        }
        return inserted;
    }

    private List<LocalTime[]> defaultSlotTimes(String mode) {
        if ("SESSION".equals(mode)) {
            return Arrays.asList(
                    new LocalTime[] {LocalTime.NOON, LocalTime.of(14, 10)},
                    new LocalTime[] {LocalTime.of(14, 30), LocalTime.of(16, 10)},
                    new LocalTime[] {LocalTime.of(16, 30), LocalTime.of(18, 40)},
                    new LocalTime[] {LocalTime.of(19, 0), LocalTime.of(21, 0)});
        }
        if (!"COURT".equals(mode)) {
            throw new BookingException("场地预约模式无效");
        }
        List<LocalTime[]> times = new ArrayList<>();
        for (int hour = 8; hour < 21; hour++) {
            times.add(new LocalTime[] {LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0)});
        }
        return times;
    }

    @Override
    public Result listVenues(String name, String category, boolean admin) {
        if (admin) {
            requireAdmin();
        }
        // 按名称、分类和开放状态筛选，管理查询直接读取数据库
        QueryWrapper<Venue> q = new QueryWrapper<>();
        if (!admin) {
            q.eq("enabled", true);
        }
        if (name != null && !name.trim().isEmpty()) {
            q.like("name", name.trim());
        }
        if (category != null && !category.isEmpty()) {
            q.eq("category", category);
        }
        if (admin) {
            return Result.ok(list(q.orderByAsc("id")));
        }
        // 将搜索条件组成独立缓存键，避免不同条件的结果混用
        String filter =
                JSONUtil.toJsonStr(
                        Arrays.asList(
                                name == null ? "" : name.trim(), category == null ? "" : category));
        return Result.ok(
                bookingCache.getList(
                        "venues:" + filter, Venue.class, () -> list(q.orderByAsc("id"))));
    }

    @Override
    public Result venueDetail(Long id) {
        Venue venue = bookingCache.get("venue:" + id, Venue.class, () -> getById(id));
        if (venue == null || !Boolean.TRUE.equals(venue.getEnabled())) {
            throw new BookingException("场馆不存在或暂未开放");
        }
        return Result.ok(venue);
    }

    @Override
    public Result listCourts(Long venueId, boolean admin) {
        if (admin) {
            requireAdmin();
        } else {
            venueDetail(venueId);
        }
        QueryWrapper<VenueCourt> q = new QueryWrapper<VenueCourt>().eq("venue_id", venueId);
        if (!admin) {
            q.eq("enabled", true);
        }
        if (admin) {
            return Result.ok(courtMapper.selectList(q.orderByAsc("id")));
        }
        return Result.ok(
                bookingCache.getList(
                        "courts:" + venueId,
                        VenueCourt.class,
                        () -> courtMapper.selectList(q.orderByAsc("id"))));
    }

    @Override
    public Result listSlots(Long courtId, LocalDate date, boolean admin) {
        if (admin) {
            requireAdmin();
        }

        // 1.限定可查询日期，确认所属场地和场馆存在
        if (date == null
                || date.isBefore(BookingRules.now().toLocalDate())
                || date.isAfter(BookingRules.now().toLocalDate().plusDays(6))) {
            throw new BookingException("请选择今天起七天内的日期");
        }
        VenueCourt court = courtMapper.selectById(courtId);
        if (court == null) {
            throw new BookingException("场地不存在");
        }
        Venue venue = getById(court.getVenueId());
        if (venue == null) {
            throw new BookingException("场馆不存在");
        }
        java.util.function.Supplier<List<VenueSlot>> loader =
                () ->
                        slotMapper.selectList(
                                new QueryWrapper<VenueSlot>()
                                        .eq("court_id", courtId)
                                        .eq("booking_date", date)
                                        .orderByAsc("start_time"));

        // 2.获取原始场次数据，管理查询旁路缓存
        List<VenueSlot> slots = admin ? loader.get() : bookingCache.getSlots(courtId, date, loader);
        LocalDateTime now = BookingRules.now();

        // 3.每次根据当前时间和剩余名额计算是否可预约
        for (VenueSlot slot : slots) {
            slot.setRemaining(Math.max(0, slot.getCapacity() - slot.getBookedCount()));
            boolean open =
                    Boolean.TRUE.equals(venue.getEnabled())
                            && Boolean.TRUE.equals(court.getEnabled())
                            && Boolean.TRUE.equals(slot.getEnabled());
            boolean started = !slot.getBookingDate().atTime(slot.getStartTime()).isAfter(now);
            slot.setAvailable(open && !started && slot.getRemaining() > 0);
            slot.setStatusText(
                    started ? "已开始" : !open ? "未开放" : slot.getRemaining() == 0 ? "已约满" : "可预约");
        }
        return Result.ok(slots);
    }

    @Override
    @Transactional
    public Result saveVenue(Venue input) {
        requireAdmin();

        // 1.校验场馆资料、分类、取消时限和开放状态
        input.setName(BookingRules.required(input.getName(), "场馆名称", 64));
        input.setAddress(BookingRules.required(input.getAddress(), "场馆地址", 255));
        input.setOpenHours(BookingRules.required(input.getOpenHours(), "开放时间", 64));
        if (!Arrays.asList("BADMINTON", "TENNIS", "SWIMMING", "BILLIARDS")
                .contains(input.getCategory())) {
            throw new BookingException("场馆分类无效");
        }
        if (input.getCancelAdvanceMinutes() == null
                || input.getCancelAdvanceMinutes() < 0
                || input.getCancelAdvanceMinutes() > 1440) {
            throw new BookingException("取消提前分钟数应为0至1440");
        }
        if (input.getEnabled() == null) {
            throw new BookingException("请选择开放状态");
        }
        if (input.getDescription() != null && input.getDescription().length() > 2000
                || input.getNotice() != null && input.getNotice().length() > 4000) {
            throw new BookingException("介绍或须知过长");
        }

        // 2.编辑已有场馆时检查未来预约，避免停用正在被使用的资源
        boolean existing = input.getId() != null;
        if (existing) {
            if (baseMapper.lockById(input.getId()) == null) {
                throw new BookingException("场馆不存在");
            }
            if (!input.getEnabled()
                    && orderMapper.futureForVenue(input.getId(), BookingRules.now()) > 0) {
                throw new BookingException("存在未来待使用预约，暂不能停用场馆");
            }
        }
        // createTime 不从客户端接收，现有创建时间也不被覆盖。
        input.setCreateTime(existing ? null : BookingRules.now());
        boolean saved = existing ? updateById(input) : save(input);
        if (!saved) {
            throw new BookingException("场馆保存失败");
        }

        // 3.数据库保存成功后登记缓存删除，等事务提交再执行
        bookingCache.catalogChanged();
        return Result.ok(input.getId());
    }

    @Override
    @Transactional
    public Result saveCourt(VenueCourt input) {
        requireAdmin();
        if (input.getVenueId() == null || baseMapper.lockById(input.getVenueId()) == null) {
            throw new BookingException("请选择有效场馆");
        }
        input.setName(BookingRules.required(input.getName(), "场地名称", 64));
        if (!Arrays.asList("COURT", "SESSION").contains(input.getMode())) {
            throw new BookingException("预约模式无效");
        }
        if (input.getCapacity() == null
                || input.getCapacity() < 1
                || input.getCapacity() > 1000
                || input.getMaxPeople() == null
                || input.getMaxPeople() < 1
                || input.getMaxPeople() > 6
                || input.getEnabled() == null) {
            throw new BookingException("请填写有效容量、人数上限及状态");
        }
        if ("COURT".equals(input.getMode()) && input.getCapacity() != 1
                || "SESSION".equals(input.getMode()) && input.getMaxPeople() != 1) {
            throw new BookingException("包场容量必须为1单，名额场次每单限1人");
        }
        boolean existing = input.getId() != null;
        if (existing) {
            VenueCourt old = courtMapper.lockById(input.getId());
            if (old == null || !old.getVenueId().equals(input.getVenueId())) {
                throw new BookingException("不能移动场地所属场馆");
            }
            if (orderMapper.futureForCourt(input.getId(), BookingRules.now()) > 0) {
                throw new BookingException("场地存在未来待使用预约，暂不能修改");
            }
            // 已发布场次的预约模式和人数约定不能改变，只影响新建场地的配置。
            if ((!old.getMode().equals(input.getMode())
                            || !old.getMaxPeople().equals(input.getMaxPeople()))
                    && slotMapper.selectCount(
                                    new QueryWrapper<VenueSlot>().eq("court_id", input.getId()))
                            > 0) {
                throw new BookingException("已有发布时段，不能更改预约模式或人数上限");
            }
        }
        input.setCreateTime(existing ? null : BookingRules.now());
        int changed = existing ? courtMapper.updateById(input) : courtMapper.insert(input);
        if (changed != 1) {
            throw new BookingException("场地保存失败");
        }
        bookingCache.catalogChanged();
        return Result.ok(input.getId());
    }

    @Override
    @Transactional
    public Result publishSlot(VenueSlot input) {
        requireAdmin();

        // 1.定位场地并锁定所属资源，校验开放状态和预约时间
        VenueCourt initial = courtMapper.selectById(input.getCourtId());
        if (initial == null) {
            throw new BookingException("场地不存在");
        }
        Venue venue = baseMapper.lockById(initial.getVenueId());
        VenueCourt court = courtMapper.lockById(initial.getId());
        if (!Boolean.TRUE.equals(venue.getEnabled()) || !Boolean.TRUE.equals(court.getEnabled())) {
            throw new BookingException("请先开放场馆和场地");
        }
        BookingRules.validateSlot(
                input.getBookingDate(),
                input.getStartTime(),
                input.getEndTime(),
                BookingRules.now());
        // 发布时容量取场地设置，不采纳请求中的已预约数、ID或其他状态。
        // 当前读可看到等待场馆锁期间其他任务刚提交的场次，避免使用旧快照漏检。
        if (!slotMapper
                .findOverlappingIds(
                        court.getId(),
                        input.getBookingDate(),
                        input.getStartTime(),
                        input.getEndTime())
                .isEmpty()) {
            throw new BookingException("该场地已有重复或重叠时段");
        }

        // 2.使用场地配置生成场次，发布成功后删除相应日期缓存
        VenueSlot slot = new VenueSlot();
        slot.setCourtId(court.getId())
                .setBookingDate(input.getBookingDate())
                .setStartTime(input.getStartTime())
                .setEndTime(input.getEndTime());
        slot.setCapacity(court.getCapacity())
                .setBookedCount(0)
                .setEnabled(true)
                .setCreateTime(BookingRules.now());
        if (slotMapper.insert(slot) != 1) {
            throw new BookingException("时段发布失败");
        }
        bookingCache.slotsChanged(slot.getCourtId(), slot.getBookingDate());
        return Result.ok(slot.getId());
    }

    @Override
    @Transactional
    public Result setSlotEnabled(Long id, boolean enabled) {
        requireAdmin();

        // 1.读取并锁定场次，已存在预约时不允许更改开放状态
        VenueSlot initial = slotMapper.selectById(id);
        if (initial == null) {
            throw new BookingException("时段不存在");
        }
        VenueCourt court = courtMapper.selectById(initial.getCourtId());
        Venue venue = baseMapper.lockById(court.getVenueId());
        VenueSlot slot = slotMapper.lockById(id);
        if (slot.getBookedCount() > 0) {
            throw new BookingException("时段已有预约，暂不能修改开放状态");
        }
        if (enabled) {
            if (!Boolean.TRUE.equals(venue.getEnabled())
                    || !Boolean.TRUE.equals(court.getEnabled())) {
                throw new BookingException("请先开放场馆和场地");
            }
            BookingRules.validateSlot(
                    slot.getBookingDate(),
                    slot.getStartTime(),
                    slot.getEndTime(),
                    BookingRules.now());
        }
        VenueSlot update = new VenueSlot();
        update.setId(id).setEnabled(enabled);
        if (slotMapper.updateById(update) != 1) {
            throw new BookingException("时段保存失败");
        }
        bookingCache.slotsChanged(slot.getCourtId(), slot.getBookingDate());
        return Result.ok();
    }
}
