package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.BookingCompanionDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.BookingOrder;
import com.hmdp.entity.BookingRequestTask;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;
import com.hmdp.mapper.BookingOrderMapper;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.mapper.VenueCourtMapper;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.mapper.VenueSlotMapper;
import com.hmdp.service.IBookingOrderService;
import com.hmdp.utils.BookingAdminAccess;
import com.hmdp.utils.BookingAdmissionRedis;
import com.hmdp.utils.BookingCache;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingOperationContext;
import com.hmdp.utils.BookingRedisGuard;
import com.hmdp.utils.BookingRules;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Resource;

@Service
public class BookingOrderServiceImpl extends ServiceImpl<BookingOrderMapper, BookingOrder>
        implements IBookingOrderService {
    @Resource
    private VenueSlotMapper slotMapper;

    @Resource
    private VenueCourtMapper courtMapper;

    @Resource
    private VenueMapper venueMapper;

    @Resource
    private BookingRequestMapper bookingRequestMapper;

    @Resource
    private BookingAdmissionRedis admission;

    @Resource
    private BookingAdminAccess bookingAdminAccess;

    @Resource
    private BookingRedisGuard bookingRedisGuard;

    @Resource
    private BookingCache bookingCache;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BookingOrder createAcceptedOrder(BookingRequestTask request) {
        // 1.消费线程不使用UserHolder，身份与本人资料来自受理时的数据库快照。
        Long userId = request.getUserId();
        BookingRules.required(request.getRealName(), "预约人姓名", 32);
        BookingRules.required(request.getStudentNo(), "预约人学号/工号", 32);

        // 2.根据时段定位所属场地和场馆
        VenueSlot initialSlot = slotMapper.selectById(request.getSlotId());
        if (initialSlot == null) {
            throw new BookingException("预约时段不存在");
        }
        VenueCourt initialCourt = courtMapper.selectById(initialSlot.getCourtId());
        if (initialCourt == null) {
            throw new BookingException("场地不存在");
        }

        // 3.按固定顺序锁定资源，重新检查最新开放状态
        // 与管理维护、取消、核销使用相同的锁顺序，保证基础业务一致性。
        Venue venue = venueMapper.lockById(initialCourt.getVenueId());
        VenueCourt court = courtMapper.lockById(initialCourt.getId());
        VenueSlot slot = slotMapper.lockById(request.getSlotId());
        if (venue == null
                || court == null
                || slot == null
                || !Boolean.TRUE.equals(venue.getEnabled())
                || !Boolean.TRUE.equals(court.getEnabled())
                || !Boolean.TRUE.equals(slot.getEnabled())) {
            throw new BookingException("该场次暂未开放预约");
        }
        if (!court.getId().equals(request.getCourtId())
                || !venue.getId().equals(request.getVenueId())
                || !slot.getBookingDate().equals(request.getBookingDate())) {
            throw new BookingException("场次资料已变化，请重新预约");
        }

        // 4.校验预约日期、同行人数和每天仅预约一次的限制
        BookingRules.validateSlot(
                slot.getBookingDate(), slot.getStartTime(), slot.getEndTime(), BookingRules.now());
        java.util.List<BookingCompanionDTO> companions =
                JSONUtil.toList(
                        JSONUtil.parseArray(request.getCompanionsJson()),
                        BookingCompanionDTO.class);
        BookingRules.validateCompanions(
                companions, request.getStudentNo(), court.getMaxPeople(), court.getMode());
        if (count(
                        new QueryWrapper<BookingOrder>()
                                .eq("user_id", userId)
                                .eq("active_date", slot.getBookingDate()))
                > 0) {
            throw new BookingException("每天仅可预约一次，请先查看或取消已有预约");
        }

        // 5.预占已在受理入口完成，这里仅以SQL条件更新最终扣减容量。
        BookingOrder order = new BookingOrder();
        order.setId(request.getId());
        if (slotMapper.acquire(slot.getId()) != 1) {
            throw new BookingException("该场次已约满，请选择其他时段");
        }

        // 6.保存账号、场馆及预约人资料快照，后续改资料不会改变历史订单
        order.setUserId(userId)
                .setVenueId(venue.getId())
                .setCourtId(court.getId())
                .setSlotId(slot.getId());
        order.setVenueName(venue.getName())
                .setCourtName(court.getName())
                .setBookingDate(slot.getBookingDate())
                .setActiveDate(slot.getBookingDate())
                .setStartTime(slot.getStartTime())
                .setEndTime(slot.getEndTime());
        order.setRealName(request.getRealName())
                .setStudentNo(request.getStudentNo())
                .setPhone(request.getPhone());
        order.setCompanionsJson(JSONUtil.toJsonStr(companions))
                .setPeopleCount(companions.size() + 1);
        order.setStatus("RESERVED")
                .setVerifyCode(
                        UUID.randomUUID()
                                .toString()
                                .replace("-", "")
                                .substring(0, 12)
                                .toUpperCase(Locale.ROOT));
        order.setCancelAdvanceMinutes(venue.getCancelAdvanceMinutes())
                .setCreateTime(BookingRules.now());

        // 7.保存预约订单，事务提交成功后删除对应场次缓存
        if (!save(order)) {
            throw new BookingException("预约失败，请重试");
        }
        bookingCache.slotsChanged(slot.getCourtId(), slot.getBookingDate());
        return order;
    }

    @Override
    public Result listOrders(String status, Integer current, boolean admin) {
        Long userId = BookingRules.currentUserId();

        // 1.校验管理权限及分页参数
        if (admin && !bookingAdminAccess.isAdmin(userId)) {
            throw new BookingException("无管理权限");
        }
        if (current == null || current < 1 || current > 100000) {
            throw new BookingException("页码无效");
        }

        // 2.普通用户仅查询本人订单，并按请求状态筛选
        QueryWrapper<BookingOrder> wrapper = new QueryWrapper<>();
        if (!admin) {
            wrapper.eq("user_id", userId);
        }
        LocalDateTime now = BookingRules.now();
        if (status != null && !status.isEmpty()) {
            if ("EXPIRED".equals(status)) {
                wrapper.eq("status", "RESERVED")
                        .apply("TIMESTAMP(booking_date, end_time) <= {0}", now);
            } else if ("RESERVED".equals(status)) {
                wrapper.eq("status", status).apply("TIMESTAMP(booking_date, end_time) > {0}", now);
            } else if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
                wrapper.eq("status", status);
            } else {
                throw new BookingException("预约状态无效");
            }
        }

        // 3.分页查询订单，补充当前可取消、可核销等展示状态
        Page<BookingOrder> page = page(new Page<>(current, 10), wrapper.orderByDesc("create_time"));
        page.getRecords().forEach(this::decorate);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    private BookingOrder ownedOrder(String id) {
        Long userId = BookingRules.currentUserId();
        BookingOrder order = getById(id);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BookingException("预约记录不存在");
        }
        return order;
    }

    @Override
    public Result orderDetail(String id) {
        return Result.ok(decorate(ownedOrder(id)));
    }

    @Override
    @Transactional
    public Result cancelOrder(String id) {
        BookingOperationContext.trackTransaction();
        // 1.确认订单属于当前用户，再加锁读取最新订单状态
        BookingOrder original = ownedOrder(id);
        venueMapper.lockById(original.getVenueId());
        BookingOrder order = baseMapper.lockById(id);
        if (order == null || !BookingRules.currentUserId().equals(order.getUserId())) {
            throw new BookingException("预约记录不存在");
        }

        // 2.检查取消时限，通过状态条件更新防止重复取消
        if (!BookingRules.canCancel(
                order.getStatus(),
                order.getBookingDate().atTime(order.getStartTime()),
                order.getCancelAdvanceMinutes(),
                BookingRules.now())) {
            throw new BookingException("当前订单不可取消，请查看取消时限或预约状态");
        }
        if (baseMapper.cancel(id, order.getUserId()) != 1) {
            throw new BookingException("订单状态已变化，请刷新");
        }
        bookingRequestMapper.releaseDate(id, order.getUserId(), BookingRules.now());

        // 3.归还数据库名额，提交后清理本订单的 Redis 标记和场次缓存
        if (slotMapper.release(order.getSlotId()) != 1) {
            throw new BookingException("名额释放失败，请联系管理员");
        }
        bookingRedisGuard.cancelled(order);
        BookingRequestTask request = new BookingRequestTask();
        request.setId(order.getId());
        request.setUserId(order.getUserId());
        request.setSlotId(order.getSlotId());
        request.setBookingDate(order.getBookingDate());
        com.hmdp.utils.BookingTransactions.afterCommit(() -> admission.release(request));
        VenueSlot slot = slotMapper.selectById(order.getSlotId());
        bookingCache.slotsChanged(slot.getCourtId(), slot.getBookingDate());
        return Result.ok();
    }

    @Override
    @Transactional
    public Result verifyOrder(String id, String verifyCode) {
        BookingOperationContext.trackTransaction();
        // 1.校验管理员身份，定位订单并加锁
        if (!bookingAdminAccess.isAdmin(BookingRules.currentUserId())) {
            throw new BookingException("无管理权限");
        }
        BookingOrder original = getById(id);
        if (original == null) {
            throw new BookingException("预约记录不存在");
        }
        venueMapper.lockById(original.getVenueId());
        BookingOrder order = baseMapper.lockById(id);

        // 2.校验核销码以及开场前30分钟至结束前的核销窗口
        if (verifyCode == null || !verifyCode.trim().equalsIgnoreCase(order.getVerifyCode())) {
            throw new BookingException("核销码不正确");
        }
        LocalDateTime now = BookingRules.now();
        if (!BookingRules.canVerify(
                order.getStatus(),
                order.getBookingDate().atTime(order.getStartTime()),
                order.getBookingDate().atTime(order.getEndTime()),
                now)) {
            throw new BookingException("仅待使用订单可在开场前30分钟至结束前核销");
        }

        // 3.仅将待使用订单更新为已完成，防止重复核销
        if (baseMapper.verify(id, now) != 1) {
            throw new BookingException("订单状态已变化，请刷新");
        }
        return Result.ok();
    }

    private BookingOrder decorate(BookingOrder order) {
        LocalDateTime now = BookingRules.now();
        // 根据当前时间计算展示状态，不修改数据库中的原订单
        LocalDateTime start = order.getBookingDate().atTime(order.getStartTime());
        LocalDateTime end = order.getBookingDate().atTime(order.getEndTime());
        if ("RESERVED".equals(order.getStatus()) && !now.isBefore(end)) {
            order.setStatus("EXPIRED");
        }
        order.setCanCancel(
                BookingRules.canCancel(
                        order.getStatus(), start, order.getCancelAdvanceMinutes(), now));
        order.setCanVerify(BookingRules.canVerify(order.getStatus(), start, end, now));
        Map<String, String> labels = new HashMap<>();
        labels.put("RESERVED", "待使用");
        labels.put("COMPLETED", "已完成");
        labels.put("CANCELLED", "已取消");
        labels.put("EXPIRED", "已过期");
        order.setStatusText(labels.get(order.getStatus()));
        order.setCompanions(
                order.getCompanionsJson() == null
                        ? Collections.emptyList()
                        : JSONUtil.toList(
                                JSONUtil.parseArray(order.getCompanionsJson()),
                                BookingCompanionDTO.class));
        return order;
    }
}
