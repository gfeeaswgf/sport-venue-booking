package com.hmdp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hmdp.aspect.ApiTimingAspect;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.BookingOrderController;
import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IBookingOrderService;
import com.hmdp.utils.BookingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 仅启动真实 AOP 代理和 MVC 测试，不加载业务连接配置。 */
@SpringJUnitConfig(ApiTimingTest.Config.class)
class ApiTimingTest {
    @Configuration
    @Import(AopAutoConfiguration.class)
    @ComponentScan(basePackageClasses = ApiTimingAspect.class)
    static class Config {
        @Bean
        BookingOrderController bookingController() {
            return new BookingOrderController();
        }

        @Bean
        IBookingOrderService bookingOrderService() {
            return mock(IBookingOrderService.class);
        }
    }

    @Autowired BookingOrderController controller;

    @Autowired IBookingOrderService orders;
    MockMvc mvc;
    Logger logger;
    Level oldLevel;
    ListAppender<ILoggingEvent> logs;
    final String body = "{\"slotId\":100,\"agreement\":true,\"companions\":[]}";

    @BeforeEach
    void setup() {
        reset(orders);
        logger = (Logger) LoggerFactory.getLogger(ApiTimingAspect.class);
        oldLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new WebExceptionAdvice())
                        .build();
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
        logger.setLevel(oldLevel);
    }

    String timing(String outcome) {
        assertEquals(1, logs.list.size(), "每次进入创建预约方法只记录一条计时日志");
        String message = logs.list.get(0).getFormattedMessage();
        assertTrue(message.contains("outcome=" + outcome));
        Matcher duration = Pattern.compile("durationMs=(\\d+\\.\\d{3})(?: |$)").matcher(message);
        assertTrue(duration.find(), message);
        assertTrue(Double.parseDouble(duration.group(1)) >= 0);
        return message;
    }

    @Test
    void bootAopRecordsCreateWithoutChangingPayloadOrLoggingSensitiveData() throws Exception {
        assertTrue(AopUtils.isAopProxy(controller));
        when(orders.createOrder(any())).thenReturn(Result.ok("order-1"));
        mvc.perform(
                        post("/booking/orders")
                                .contentType("application/json")
                                .header("authorization", "secret-token")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("order-1"))
                .andExpect(jsonPath("$.durationMs").doesNotExist());
        String message = timing("SUCCESS");
        assertTrue(message.contains("api=创建预约"));
        assertTrue(message.contains("method=POST"));
        assertTrue(message.contains("path=/booking/orders"));
        assertTrue(message.contains("exception=-"));
        assertFalse(message.contains("secret-token"));
        assertFalse(message.contains("slotId"));
        assertFalse(message.contains("order-1"));
        verify(orders, times(1)).createOrder(any());
    }

    @Test
    void returnedBusinessFailureIsRejectedAndOriginalObjectIsPreserved() {
        Result original = Result.fail("场次已满");
        when(orders.createOrder(any())).thenReturn(original);
        assertSame(original, controller.create(new BookingRequestDTO()));
        timing("REJECTED");
    }

    @Test
    void businessExceptionStillUsesExistingAdviceAndDoesNotLogItsMessage() throws Exception {
        when(orders.createOrder(any())).thenThrow(new BookingException("敏感姓名和手机号不进入计时日志"));
        mvc.perform(post("/booking/orders").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("敏感姓名和手机号不进入计时日志"));
        String message = timing("REJECTED");
        assertTrue(message.contains("exception=BookingException"));
        assertFalse(message.contains("敏感姓名"));
    }

    @Test
    void uniqueConstraintConflictIsBusinessRejection() throws Exception {
        when(orders.createOrder(any())).thenThrow(new DuplicateKeyException("sensitive sql"));
        mvc.perform(post("/booking/orders").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
        String message = timing("REJECTED");
        assertTrue(message.contains("exception=DuplicateKeyException"));
        assertFalse(message.contains("sensitive sql"));
    }

    @Test
    void systemExceptionIsLoggedAndPropagatedWithoutWrapping() {
        IllegalStateException original = new IllegalStateException("private database detail");
        when(orders.createOrder(any())).thenThrow(original);
        assertSame(
                original,
                assertThrows(
                        IllegalStateException.class,
                        () -> controller.create(new BookingRequestDTO())));
        String message = timing("ERROR");
        assertTrue(message.contains("exception=IllegalStateException"));
        assertFalse(message.contains("private database detail"));
    }

    @Test
    void errorsAlsoPropagateWithoutWrappingAndAreTimedOnce() {
        AssertionError original = new AssertionError("private failure detail");
        when(orders.createOrder(any())).thenThrow(original);
        assertSame(
                original,
                assertThrows(
                        AssertionError.class, () -> controller.create(new BookingRequestDTO())));
        assertTrue(timing("ERROR").contains("exception=AssertionError"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void timingIsReportedAfterTransactionAndItsSynchronousCallbacks(boolean commit) {
        List<String> events = new ArrayList<>();
        AbstractPlatformTransactionManager manager =
                new AbstractPlatformTransactionManager() {
                    @Override
                    protected Object doGetTransaction() {
                        return new Object();
                    }

                    @Override
                    protected void doBegin(Object transaction, TransactionDefinition definition) {}

                    @Override
                    protected void doCommit(DefaultTransactionStatus status) {
                        assertTrue(logs.list.isEmpty());
                        events.add("commit");
                    }

                    @Override
                    protected void doRollback(DefaultTransactionStatus status) {
                        assertTrue(logs.list.isEmpty());
                        events.add("rollback");
                    }
                };
        BookingException rejected = new BookingException("rollback");
        when(orders.createOrder(any()))
                .thenAnswer(
                        call ->
                                new TransactionTemplate(manager)
                                        .execute(
                                                status -> {
                                                    TransactionSynchronizationManager
                                                            .registerSynchronization(
                                                                    new TransactionSynchronization() {
                                                                        @Override
                                                                        public void afterCommit() {
                                                                            assertTrue(
                                                                                    logs.list
                                                                                            .isEmpty());
                                                                            events.add(
                                                                                    "afterCommit");
                                                                        }

                                                                        @Override
                                                                        public void afterCompletion(
                                                                                int completion) {
                                                                            assertTrue(
                                                                                    logs.list
                                                                                            .isEmpty());
                                                                            events.add(
                                                                                    "afterCompletion");
                                                                            assertEquals(
                                                                                    commit
                                                                                            ? STATUS_COMMITTED
                                                                                            : STATUS_ROLLED_BACK,
                                                                                    completion);
                                                                        }
                                                                    });
                                                    if (!commit) {
                                                        throw rejected;
                                                    }
                                                    return Result.ok("transaction-completed");
                                                }));
        if (commit) {
            assertEquals(
                    "transaction-completed", controller.create(new BookingRequestDTO()).getData());
            assertEquals(Arrays.asList("commit", "afterCommit", "afterCompletion"), events);
        } else {
            assertSame(
                    rejected,
                    assertThrows(
                            BookingException.class,
                            () -> controller.create(new BookingRequestDTO())));
            assertEquals(Arrays.asList("rollback", "afterCompletion"), events);
        }
        timing(commit ? "SUCCESS" : "REJECTED");
    }

    @Test
    void listAndCancelAreNotIncludedInTimingScope() throws Exception {
        when(orders.listOrders(any(), any(), anyBoolean()))
                .thenReturn(Result.ok(Collections.emptyList()));
        when(orders.cancelOrder("order-1")).thenReturn(Result.ok());
        mvc.perform(get("/booking/orders")).andExpect(status().isOk());
        mvc.perform(put("/booking/orders/order-1/cancel")).andExpect(status().isOk());
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void invalidJsonBeforeControllerEntryIsNotReportedAsMethodTiming() throws Exception {
        mvc.perform(post("/booking/orders").contentType("application/json").content("{broken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
        assertTrue(logs.list.isEmpty());
        verifyNoInteractions(orders);
    }
}
