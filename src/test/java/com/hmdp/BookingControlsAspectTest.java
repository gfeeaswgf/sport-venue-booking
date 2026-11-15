package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hmdp.aspect.ApiTimingAspect;
import com.hmdp.aspect.IdempotentAspect;
import com.hmdp.aspect.OperationLogAspect;
import com.hmdp.aspect.RateLimitAspect;
import com.hmdp.config.BookingRequestFilter;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.AdminController;
import com.hmdp.controller.BookingOrderController;
import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.IdempotencyRecord;
import com.hmdp.dto.OperationLogDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IBookingOrderService;
import com.hmdp.service.IOperationLogService;
import com.hmdp.service.IVenueService;
import com.hmdp.utils.BookingControlException;
import com.hmdp.utils.BookingOperationContext;
import com.hmdp.utils.BookingRequestRedis;
import com.hmdp.utils.UserHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 真正启动全部请求切面与 MVC；只替换数据库与 Redis 网络边界。 */
@SpringJUnitConfig(BookingControlsAspectTest.Config.class)
class BookingControlsAspectTest {
    @Configuration
    @Import({
        AopAutoConfiguration.class,
        OperationLogAspect.class,
        ApiTimingAspect.class,
        RateLimitAspect.class,
        IdempotentAspect.class
    })
    static class Config {
        @Bean
        BookingOrderController controller() {
            return new BookingOrderController();
        }

        @Bean
        IBookingOrderService bookingOrderService() {
            return mock(IBookingOrderService.class);
        }

        @Bean
        BookingRequestRedis requestRedis() {
            return mock(BookingRequestRedis.class);
        }

        @Bean
        IOperationLogService operationLogService() {
            return mock(IOperationLogService.class);
        }

        @Bean
        AdminController adminController() {
            return new AdminController();
        }

        @Bean
        IVenueService venueService() {
            return mock(IVenueService.class);
        }
    }

    @Autowired
    BookingOrderController controller;

    @Autowired
    IBookingOrderService orders;

    @Autowired
    BookingRequestRedis redis;

    @Autowired
    IOperationLogService logs;

    @Autowired
    AdminController admin;

    @Autowired
    IVenueService venues;

    MockMvc mvc;
    List<OperationLogDTO> recorded;
    AtomicInteger created;
    final String key = "f7a391cf-2946-4bcf-a752-a879612f5f4e";
    final String body = "{\"slotId\":100,\"agreement\":true,\"companions\":[]}";

    @BeforeEach
    void setup() {
        reset(orders, redis, logs, venues);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        recorded = new ArrayList<>();
        created = new AtomicInteger();
        doAnswer(
                        call -> {
                            recorded.add(call.getArgument(0));
                            return null;
                        })
                .when(logs)
                .saveLog(any());
        when(redis.begin(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyRecord("ACQUIRED", null));
        when(redis.complete(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(orders.createOrder(any())).thenAnswer(call -> transaction(false, false));
        mvc =
                MockMvcBuilders.standaloneSetup(controller, admin)
                        .setControllerAdvice(new WebExceptionAdvice())
                        .addFilters(new BookingRequestFilter())
                        .build();
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
        assertNull(BookingOperationContext.current(), "请求结束必须清理线程上下文");
    }

    @Test
    void sameRequestReplaysOrderWithoutExecutingBusinessAgain() throws Exception {
        when(redis.begin(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyRecord("ACQUIRED", null))
                .thenReturn(new IdempotencyRecord("SUCCESS", "order-1"));
        create().andExpect(jsonPath("$.data").value("order-1"))
                .andExpect(header().exists("X-Request-Id"));
        create().andExpect(jsonPath("$.data").value("order-1"));
        assertEquals(1, created.get());
        assertEquals("EXECUTED", recorded.get(0).getExecutionType());
        assertEquals("REPLAY", recorded.get(1).getExecutionType());
        assertEquals("order-1", recorded.get(1).getTargetId());
        assertNotEquals(recorded.get(0).getRequestId(), recorded.get(1).getRequestId());
        assertEquals(
                recorded.get(0).getIdempotencyKeyHash(), recorded.get(1).getIdempotencyKeyHash());
    }

    @Test
    void missingKeyIsRejectedWithoutCreatingAnOrder() throws Exception {
        mvc.perform(post("/booking/orders").contentType("application/json").content(body))
                .andExpect(jsonPath("$.success").value(false));
        assertEquals(0, created.get());
        assertEquals("REJECTED", recorded.get(0).getOutcome());
    }

    @Test
    void processingAndConflictingRequestsNeverExecuteBusiness() throws Exception {
        when(redis.begin(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyRecord("PROCESSING", null))
                .thenReturn(new IdempotencyRecord("CONFLICT", null));
        create().andExpect(jsonPath("$.success").value(false));
        create().andExpect(jsonPath("$.success").value(false));
        assertEquals(0, created.get());
        assertEquals("IN_PROGRESS", recorded.get(0).getExecutionType());
        assertEquals("CONFLICT", recorded.get(1).getExecutionType());
        verify(redis, never()).release(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void thirdRequestIsLimitedEvenWhenTheResultWasCached() throws Exception {
        when(redis.begin(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyRecord("SUCCESS", "order-1"));
        doNothing()
                .doNothing()
                .doThrow(new BookingControlException("LIMITED", "提交频繁"))
                .when(redis)
                .checkRate(anyLong(), anyLong(), anyString());
        create().andExpect(jsonPath("$.success").value(true));
        create().andExpect(jsonPath("$.success").value(true));
        create().andExpect(jsonPath("$.success").value(false));
        assertEquals("LIMITED", recorded.get(2).getExecutionType());
        verify(redis, times(2)).begin(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void rollbackReleasesOnlyTheCurrentExecution() throws Exception {
        when(orders.createOrder(any())).thenAnswer(call -> transaction(true, false));
        create().andExpect(jsonPath("$.success").value(false));
        verify(redis).release(eq(7L), eq(key), anyString(), anyString());
        verify(redis, never())
                .complete(anyLong(), anyString(), anyString(), anyString(), anyString());
        assertEquals("ERROR", recorded.get(0).getOutcome());
    }

    @Test
    void exceptionAfterCommitDoesNotReleaseTheProcessingRecord() throws Exception {
        when(orders.createOrder(any())).thenAnswer(call -> transaction(false, true));
        create().andExpect(jsonPath("$.success").value(false))
                .andExpect(
                        jsonPath("$.errorMsg").value(org.hamcrest.Matchers.containsString("我的预约")));
        verify(redis, never()).release(anyLong(), anyString(), anyString(), anyString());
        assertEquals("UNKNOWN", recorded.get(0).getOutcome());
    }

    @Test
    void uncertainCommitWarnsUserToCheckOrdersAndDoesNotRelease() throws Exception {
        when(orders.createOrder(any())).thenAnswer(call -> transaction(false, false, true));
        create().andExpect(jsonPath("$.success").value(false))
                .andExpect(
                        jsonPath("$.errorMsg").value(org.hamcrest.Matchers.containsString("我的预约")));
        verify(redis, never()).release(anyLong(), anyString(), anyString(), anyString());
        assertEquals("UNKNOWN", recorded.get(0).getOutcome());
    }

    @Test
    void serviceChangesToCompanionsDoNotChangeTheFingerprintUsedToConfirm() throws Exception {
        when(orders.createOrder(any()))
                .thenAnswer(
                        call -> {
                            BookingRequestDTO request = call.getArgument(0);
                            return new TransactionTemplate(new TestTransactionManager())
                                    .execute(
                                            status -> {
                                                BookingOperationContext.trackTransaction();
                                                request.getCompanions()
                                                        .get(0)
                                                        .setName("changed-inside-service");
                                                return Result.ok("order-1");
                                            });
                        });
        mvc.perform(
                        post("/booking/orders")
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(
                                        "{\"slotId\":100,\"agreement\":true,\"companions\":[{\"name\":\"name\",\"studentNo\":\"number\"}]}"))
                .andExpect(jsonPath("$.success").value(true));
        org.mockito.ArgumentCaptor<String> fingerprints =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).begin(eq(7L), eq(key), fingerprints.capture(), anyString());
        verify(redis).complete(eq(7L), eq(key), fingerprints.capture(), anyString(), eq("order-1"));
        assertEquals(fingerprints.getAllValues().get(0), fingerprints.getAllValues().get(1));
    }

    @Test
    void successfulOrderSurvivesCacheAndAuditWriteFailures() throws Exception {
        when(redis.complete(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("cache offline"));
        doThrow(new IllegalStateException("audit offline")).when(logs).saveLog(any());
        create().andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("order-1"));
        assertEquals(1, created.get());
        verify(redis, never()).release(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void cancellationIsAuditedWithoutRecordingTheRequestBodyOrToken() throws Exception {
        when(orders.cancelOrder("order-1")).thenReturn(Result.ok());
        mvc.perform(put("/booking/orders/order-1/cancel").header("authorization", "secret-token"))
                .andExpect(jsonPath("$.success").value(true));
        assertEquals("CANCEL_BOOKING", recorded.get(0).getOperation());
        assertEquals("order-1", recorded.get(0).getTargetId());
        String json = cn.hutool.json.JSONUtil.toJsonStr(recorded.get(0));
        assertFalse(json.contains("secret-token"));
        assertFalse(json.contains("authorization"));
        verifyNoInteractions(redis);
    }

    @Test
    void adminCreationAndVerificationAreAuditedWithoutLeakingTheCode() throws Exception {
        when(venues.saveVenue(any())).thenReturn(Result.ok(1001L));
        mvc.perform(
                        post("/admin/venues")
                                .contentType("application/json")
                                .content("{\"name\":\"测试场馆\"}"))
                .andExpect(jsonPath("$.success").value(true));
        assertEquals("SAVE_VENUE", recorded.get(0).getOperation());
        assertEquals("1001", recorded.get(0).getTargetId());
        when(orders.verifyOrder("order-1", "private-code")).thenReturn(Result.ok());
        mvc.perform(put("/admin/orders/order-1/verify").param("code", "private-code"))
                .andExpect(jsonPath("$.success").value(true));
        assertEquals("VERIFY_BOOKING", recorded.get(1).getOperation());
        assertEquals("/admin/orders/{id}/verify", recorded.get(1).getPathPattern());
        assertFalse(cn.hutool.json.JSONUtil.toJsonStr(recorded).contains("private-code"));
        verifyNoInteractions(redis);
    }

    @Test
    void preoccupationFailureDoesNotCallBusinessAndStillCleansTheRequestContext() throws Exception {
        when(redis.begin(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new BookingControlException("REDIS_UNAVAILABLE", "暂不可用"));
        create().andExpect(jsonPath("$.success").value(false));
        assertEquals(0, created.get());
        verifyNoInteractions(orders);
        assertEquals("REDIS_UNAVAILABLE", recorded.get(0).getReasonCode());
    }

    private org.springframework.test.web.servlet.ResultActions create() throws Exception {
        return mvc.perform(
                post("/booking/orders")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body));
    }

    private Result transaction(boolean fail, boolean failAfterCommit) {
        return transaction(fail, failAfterCommit, false);
    }

    private Result transaction(boolean fail, boolean failAfterCommit, boolean uncertainCommit) {
        TestTransactionManager manager = new TestTransactionManager();
        manager.uncertainCommit = uncertainCommit;
        TransactionTemplate template = new TransactionTemplate(manager);
        return template.execute(
                status -> {
                    BookingOperationContext.trackTransaction();
                    if (failAfterCommit) {
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        throw new IllegalStateException("commit callback failed");
                                    }
                                });
                    }
                    if (fail) {
                        throw new IllegalStateException("business failed");
                    }
                    return Result.ok("order-" + created.incrementAndGet());
                });
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        boolean uncertainCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (uncertainCommit) {
                throw new org.springframework.transaction.TransactionSystemException(
                        "Commit result unknown");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
