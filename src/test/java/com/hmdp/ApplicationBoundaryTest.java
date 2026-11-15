package com.hmdp;

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

import static org.junit.jupiter.api.Assertions.*;

/** 防止打包残留旧后台消费者或旧业务接口，无需连接数据库/Redis。 */
class ApplicationBoundaryTest {
    private Set<String> components(Class<? extends Annotation> annotation) {
        ClassPathScanningCandidateComponentProvider scan =
                new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(annotation));
        return scan.findCandidateComponents("com.hmdp").stream()
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
                                "BookingOrderServiceImpl")),
                components(Service.class));
    }

    @Test
    void legacyQueueScriptsMapperAndIndependentRedisClientAreAbsentFromRuntime() {
        assertFalse(new ClassPathResource("seckill.lua").exists());
        assertFalse(new ClassPathResource("unlock.lua").exists());
        assertFalse(new ClassPathResource("mapper/VoucherMapper.xml").exists());
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.hmdp.config.RedissonConfig"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.redisson.Redisson"));
    }
}
