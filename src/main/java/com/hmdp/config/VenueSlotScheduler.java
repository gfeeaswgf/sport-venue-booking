package com.hmdp.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Venue;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.service.IVenueService;
import com.hmdp.utils.BookingRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.Resource;

/** 根据服务端日期滚动发布未来七天场次，无需额外数据库定时事件。 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "booking.auto-slots.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class VenueSlotScheduler {
    @Resource
    private VenueMapper venueMapper;

    @Resource
    private IVenueService venueService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshSlots();
    }

    // 每个整点检查，包含北京时间午夜；失败后下一小时可自动重试。
    @Scheduled(cron = "${booking.auto-slots.cron:0 0 * * * *}", zone = "Asia/Shanghai")
    public void refreshSlots() {
        List<Venue> venues;
        try {
            venues =
                    venueMapper.selectList(
                            new QueryWrapper<Venue>().eq("enabled", true).orderByAsc("id"));
        } catch (RuntimeException e) {
            log.error("自动补齐场次未完成，请检查数据库连接和预约表初始化；下次定时任务会重试", e);
            return;
        }
        LocalDateTime now = BookingRules.now();
        for (Venue venue : venues) {
            try {
                int added = venueService.ensureRollingSlots(venue.getId(), now);
                if (added > 0) {
                    log.info(
                            "场馆 {} 自动补齐了 {} 个场次，预约日期窗口为 {} 至 {}",
                            venue.getId(),
                            added,
                            now.toLocalDate(),
                            now.toLocalDate().plusDays(6));
                }
            } catch (RuntimeException e) {
                log.error("场馆 {} 自动补齐场次失败，其他场馆继续处理，下次任务会重试", venue.getId(), e);
            }
        }
    }
}
