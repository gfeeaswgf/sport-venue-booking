package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.BookingProfileDTO;
import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.BookingSubmissionDTO;
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
import com.hmdp.service.IAsyncBookingService;
import com.hmdp.service.IBookingRequestPersistenceService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.BookingAdmissionRedis;
import com.hmdp.utils.BookingControlException;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingOperationContext;
import com.hmdp.utils.BookingRules;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Resource;

@Service
public class AsyncBookingServiceImpl implements IAsyncBookingService {
    @Resource
    private BookingRequestMapper requests;

    @Resource
    private BookingOrderMapper orders;

    @Resource
    private VenueSlotMapper slots;

    @Resource
    private VenueCourtMapper courts;

    @Resource
    private VenueMapper venues;

    @Resource
    private IUserService users;

    @Resource
    private IBookingRequestPersistenceService persistence;

    @Resource
    private BookingAdmissionRedis admission;

    @Override
    public BookingSubmissionDTO accept(BookingRequestDTO input, String key, String fingerprint) {
        // 1.先查持久化幂等记录，缓存过期/重启也不会把旧请求当成新请求。
        Long userId = BookingRules.currentUserId();
        BookingRequestTask existing = requests.byKey(userId, key);
        if (existing != null) {
            return replay(existing, fingerprint);
        }

        // 2.只做快速校验并冻结本人资料；完整订单事务留给消费者。
        BookingProfileDTO profile = (BookingProfileDTO) users.queryProfile().getData();
        BookingRules.required(profile.getRealName(), "姓名（请先完善资料）", 32);
        BookingRules.required(profile.getStudentNo(), "学号/工号（请先完善资料）", 32);
        VenueSlot slot = slots.selectById(input.getSlotId());
        VenueCourt court = slot == null ? null : courts.selectById(slot.getCourtId());
        Venue venue = court == null ? null : venues.selectById(court.getVenueId());
        if (slot == null
                || court == null
                || venue == null
                || !Boolean.TRUE.equals(slot.getEnabled())
                || !Boolean.TRUE.equals(court.getEnabled())
                || !Boolean.TRUE.equals(venue.getEnabled())) {
            throw new BookingException("该场次暂未开放预约");
        }
        LocalDateTime now = BookingRules.now();
        BookingRules.validateSlot(
                slot.getBookingDate(), slot.getStartTime(), slot.getEndTime(), now);
        BookingRules.validateCompanions(
                input.getCompanions(),
                profile.getStudentNo(),
                court.getMaxPeople(),
                court.getMode());
        Integer active =
                orders.selectCount(
                        new QueryWrapper<BookingOrder>()
                                .eq("user_id", userId)
                                .eq("active_date", slot.getBookingDate()));
        if (active != null && active > 0) {
            throw new BookingException("每天仅可预约一次，请先查看或取消已有预约");
        }

        // 3.请求编号预先确定，后面订单使用同一编号，消息只传这个编号。
        BookingRequestTask task = new BookingRequestTask();
        task.setId(UUID.randomUUID().toString().replace("-", ""));
        task.setUserId(userId);
        task.setIdempotencyKey(key);
        task.setRequestHash(fingerprint);
        task.setVenueId(venue.getId());
        task.setCourtId(court.getId());
        task.setSlotId(slot.getId());
        task.setBookingDate(slot.getBookingDate());
        task.setActiveDate(slot.getBookingDate());
        task.setRealName(profile.getRealName());
        task.setStudentNo(profile.getStudentNo());
        task.setPhone(profile.getPhone());
        task.setCompanionsJson(JSONUtil.toJsonStr(input.getCompanions()));
        task.setStatus("ACCEPTED");
        task.setPublishCount(0);
        task.setNextPublishTime(now);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        LocalDateTime start = slot.getBookingDate().atTime(slot.getStartTime());
        task.setExpireTime(start.isBefore(now.plusMinutes(15)) ? start : now.plusMinutes(15));

        // 4.Redis只初始化缺失的余量，再原子预占，绝不覆盖其他请求已扣的库存。
        // 取消/失败事务已经提交而Redis回调丢失时，以持久化状态校准旧标记。
        String ownerId = admission.markerOwner(task);
        if (ownerId != null) {
            BookingRequestTask owner = requests.selectById(ownerId);
            if (owner != null
                    && userId.equals(owner.getUserId())
                    && task.getBookingDate().equals(owner.getBookingDate())
                    && owner.getActiveDate() == null) {
                admission.release(owner);
            }
        }
        Integer remaining = requests.remainingForAdmission(slot.getId());
        try {
            admission.reserve(task, remaining == null ? 0 : remaining);
        } catch (BookingException failure) {
            existing = requests.byKey(userId, key);
            if (existing != null) {
                return replay(existing, fingerprint);
            }
            throw failure;
        }

        // 5.轻量请求事务提交才返回已受理；后台调度不依赖本进程的内存队列。
        try {
            persistence.save(task);
        } catch (RuntimeException failure) {
            BookingOperationContext.State context = BookingOperationContext.current();
            if (context == null
                    || "NONE".equals(context.getTransactionState())
                    || "ROLLED_BACK".equals(context.getTransactionState())) {
                admission.release(task);
            }
            existing = requests.byKey(userId, key);
            if (existing != null) {
                if (!existing.getId().equals(task.getId())) {
                    admission.release(task);
                }
                return replay(existing, fingerprint);
            }
            if (failure instanceof DuplicateKeyException) {
                throw new BookingException("当天已有预约请求，请在我的预约查看处理结果");
            }
            throw new BookingControlException("RESULT_UNCONFIRMED", "请求结果尚未确认，请使用原请求标识重试或查看我的预约");
        }
        return BookingSubmissionDTO.from(task);
    }

    @Override
    public BookingSubmissionDTO result(String requestId) {
        Long userId = BookingRules.currentUserId();
        if (requestId == null || !requestId.matches("[a-f0-9]{32}")) {
            throw new BookingException("预约请求不存在");
        }
        BookingSubmissionDTO cached = admission.cachedResult(userId, requestId);
        if (cached != null) {
            return cached;
        }
        BookingRequestTask task = requests.selectById(requestId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BookingException("预约请求不存在");
        }
        admission.cacheResult(task);
        return BookingSubmissionDTO.from(task);
    }

    @Override
    public List<BookingSubmissionDTO> recent() {
        return requests.recent(BookingRules.currentUserId()).stream()
                .map(BookingSubmissionDTO::from)
                .collect(Collectors.toList());
    }

    private BookingSubmissionDTO replay(BookingRequestTask task, String fingerprint) {
        if (!fingerprint.equals(task.getRequestHash())) {
            throw new BookingControlException("CONFLICT", "同一请求标识不能提交不同预约内容");
        }
        BookingOperationContext.State context = BookingOperationContext.current();
        if (context != null) {
            context.setExecutionType("REPLAY");
        }
        return BookingSubmissionDTO.from(task);
    }
}
