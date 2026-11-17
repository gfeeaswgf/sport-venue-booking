package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** 防止打包残留旧团购组件，允许预约请求和MQ恢复组件，无需业务连接。 */
class ApplicationBoundaryTest {
    private Set<String> components(Class<? extends Annotation> annotation) {
        ClassPathScanningCandidateComponentProvider scan =
                new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(annotation));
        // 只扫描生产包，独立MVC测试夹具不应成为应用边界的一部分。
        return (annotation == RestController.class
                        ? Arrays.asList("com.hmdp.controller")
                        : Arrays.asList("com.hmdp.service", "com.hmdp.mq"))
                .stream()
                        .flatMap(basePackage -> scan.findCandidateComponents(basePackage).stream())
                        .map(bean -> bean.getBeanClassName())
                        .map(name -> name.substring(name.lastIndexOf('.') + 1))
                        .collect(Collectors.toSet());
    }

    @Test
    void applicationOnlyExposesBookingAndAccountControllers() {
        assertEquals(
                new HashSet<>(
                        Arrays.asList(
                                "AdminController",
                                "VenueController",
                                "BookingOrderController",
                                "BookingRequestController",
                                "UserController")),
                components(RestController.class));
    }

    @Test
    void onlyAccountAndBookingServicesCanStart() {
        assertEquals(
                new HashSet<>(
                        Arrays.asList(
                                "UserServiceImpl",
                                "UserInfoServiceImpl",
                                "VenueServiceImpl",
                                "OperationLogServiceImpl",
                                "AsyncBookingServiceImpl",
                                "BookingRequestPersistenceServiceImpl",
                                "BookingRequestWorkerServiceImpl",
                                "BookingResultServiceImpl",
                                "RocketMqBookingMessageService",
                                "BookingOrderServiceImpl")),
                components(Service.class));
    }

    @Test
    void legacyQueueScriptsMapperAndIndependentRedisClientAreAbsentFromRuntime() {
        assertFalse(new ClassPathResource("seckill.lua").exists());
        assertFalse(new ClassPathResource("unlock.lua").exists());
        assertFalse(new ClassPathResource("mapper/VoucherMapper.xml").exists());
        assertFalse(new ClassPathResource("booking-reserve.lua").exists());
        assertFalse(new ClassPathResource("booking-complete.lua").exists());
        assertFalse(new ClassPathResource("booking-created-stat.lua").exists());
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.hmdp.utils.BookingProcessingMetrics"));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.hmdp.utils.BookingRedisGuard"));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.hmdp.config.RedissonConfig"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.redisson.Redisson"));
    }
}
