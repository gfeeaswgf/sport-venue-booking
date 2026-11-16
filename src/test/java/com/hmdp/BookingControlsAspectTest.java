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
import com.hmdp.dto.BookingSubmissionDTO;
import com.hmdp.dto.OperationLogDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IAsyncBookingService;
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
import org.mockito.InOrder;
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

/** 真正启动全部请求切面和MVC，持久受理及网络连接仅在边界替换。 */
@SpringJUnitConfig(BookingControlsAspectTest.Config.class)
class BookingControlsAspectTest {
    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";
    private static final String FINGERPRINT =
            "7d918a4a949ca3f4761a4a8750905bfb7256ac258563dd804f5477b115b4a795";

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
        IAsyncBookingService asyncBookingService() {
            return mock(IAsyncBookingService.class);
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

    @Autowired BookingOrderController controller;
    @Autowired IBookingOrderService orders;
    @Autowired IAsyncBookingService async;
    @Autowired BookingRequestRedis redis;
    @Autowired IOperationLogService logs;
    @Autowired AdminController admin;
    @Autowired IVenueService venues;

    MockMvc mvc;
    List<OperationLogDTO> recorded;
    final String key = "f7a391cf-2946-4bcf-a752-a879612f5f4e";
    final String body = "{\"slotId\":100,\"agreement\":true,\"companions\":[]}";

    @BeforeEach
    void setup() {
        reset(orders, async, redis, logs, venues);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        recorded = new ArrayList<>();
        doAnswer(
                        call -> {
                            recorded.add(call.getArgument(0));
                            return null;
                        })
                .when(logs)
                .saveLog(any());
        when(async.accept(any(), anyString(), anyString())).thenReturn(processing());
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
    void acceptedResponseIsProcessingWithRequestIdAndSubmitAudit() throws Exception {
        create().andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").value("PROCESSING"))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.orderId").doesNotExist())
                .andExpect(header().exists("X-Request-Id"));

        verify(async).accept(any(BookingRequestDTO.class), eq(key), eq(FINGERPRINT));
        verifyNoInteractions(orders);
        assertEquals("SUBMIT_BOOKING", recorded.get(0).getOperation());
        assertEquals("REQUEST", recorded.get(0).getTargetType());
        assertEquals(REQUEST_ID, recorded.get(0).getTargetId());
        assertEquals("SUCCESS", recorded.get(0).getOutcome());
    }

    @Test
    void retriesReachDurableAdmissionWithStableKeyAndFingerprint() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(async.accept(any(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            if (calls.incrementAndGet() > 1) {
                                BookingOperationContext.current().setExecutionType("REPLAY");
                            }
                            return processing();
                        });

        create().andExpect(jsonPath("$.data.requestId").value(REQUEST_ID));
        create().andExpect(jsonPath("$.data.requestId").value(REQUEST_ID));

        verify(async, times(2)).accept(any(), eq(key), eq(FINGERPRINT));
        verify(redis, times(2)).checkRate(eq(7L), eq(100L), anyString());
        verifyNoMoreInteractions(redis);
        assertEquals("EXECUTED", recorded.get(0).getExecutionType());
        assertEquals("REPLAY", recorded.get(1).getExecutionType());
        assertNotEquals(recorded.get(0).getRequestId(), recorded.get(1).getRequestId());
        assertEquals(
                recorded.get(0).getIdempotencyKeyHash(), recorded.get(1).getIdempotencyKeyHash());
    }

    @Test
    void missingKeyIsRejectedBeforeAdmissionButStillRateCounted() throws Exception {
        mvc.perform(post("/booking/orders").contentType("application/json").content(body))
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(async, orders);
        verify(redis).checkRate(eq(7L), eq(100L), anyString());
        assertEquals("REJECTED", recorded.get(0).getOutcome());
        assertEquals("INVALID_KEY", recorded.get(0).getReasonCode());
    }

    @Test
    void processingRetryIsSuccessfulAndConflictingPayloadIsRejected() throws Exception {
        when(async.accept(any(), anyString(), anyString()))
                .thenReturn(processing())
                .thenThrow(new BookingControlException("CONFLICT", "同一请求标识不能提交不同预约内容"));

        create().andExpect(jsonPath("$.data.state").value("PROCESSING"));
        create().andExpect(jsonPath("$.success").value(false));

        assertEquals("SUCCESS", recorded.get(0).getOutcome());
        assertEquals("CONFLICT", recorded.get(1).getExecutionType());
        verifyNoInteractions(orders);
    }

    @Test
    void thirdRequestIsLimitedBeforeDurableIdempotencyReplay() throws Exception {
        doNothing()
                .doNothing()
                .doThrow(new BookingControlException("LIMITED", "提交频繁"))
                .when(redis)
                .checkRate(anyLong(), anyLong(), anyString());

        create().andExpect(jsonPath("$.success").value(true));
        create().andExpect(jsonPath("$.success").value(true));
        create().andExpect(jsonPath("$.success").value(false));

        InOrder order = inOrder(redis, async);
        order.verify(redis).checkRate(eq(7L), eq(100L), anyString());
        order.verify(async).accept(any(), eq(key), eq(FINGERPRINT));
        order.verify(redis).checkRate(eq(7L), eq(100L), anyString());
        order.verify(async).accept(any(), eq(key), eq(FINGERPRINT));
        order.verify(redis).checkRate(eq(7L), eq(100L), anyString());
        verify(async, times(2)).accept(any(), anyString(), anyString());
        assertEquals("LIMITED", recorded.get(2).getExecutionType());
    }

    @Test
    void rolledBackAdmissionIsAuditedAsErrorWithoutLegacyRedisIdempotencyWrites() throws Exception {
        when(async.accept(any(), anyString(), anyString()))
                .thenAnswer(call -> transaction(true, false, false));

        create().andExpect(jsonPath("$.success").value(false));

        assertEquals("ERROR", recorded.get(0).getOutcome());
        verify(redis).checkRate(eq(7L), eq(100L), anyString());
        verifyNoMoreInteractions(redis);
    }

    @Test
    void callbackFailureAfterCommitIsAuditedUnknown() throws Exception {
        when(async.accept(any(), anyString(), anyString()))
                .thenAnswer(call -> transaction(false, true, false));

        create().andExpect(jsonPath("$.success").value(false));

        assertEquals("UNKNOWN", recorded.get(0).getOutcome());
    }

    @Test
    void uncertainCommitIsNeverAuditedAsRolledBack() throws Exception {
        when(async.accept(any(), anyString(), anyString()))
                .thenAnswer(call -> transaction(false, false, true));

        create().andExpect(jsonPath("$.success").value(false));

        assertEquals("UNKNOWN", recorded.get(0).getOutcome());
    }

    @Test
    void serviceNormalizationCannotMutateTheFrozenFingerprint() throws Exception {
        when(async.accept(any(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            BookingRequestDTO input = call.getArgument(0);
                            input.getCompanions().get(0).setName("changed-inside-service");
                            assertEquals(
                                    "a88d9f2415868ea3c1124ed4fcf00a8f00c0ee9df8cfb07632ef2278bd260f53",
                                    call.getArgument(2));
                            assertEquals(
                                    call.getArgument(2),
                                    BookingOperationContext.current().getRequestFingerprint());
                            return processing();
                        });

        mvc.perform(
                        post("/booking/orders")
                                .header("Idempotency-Key", key.toUpperCase())
                                .contentType("application/json")
                                .content(
                                        "{\"slotId\":100,\"agreement\":true,\"companions\":[{\"name\":\"name\",\"studentNo\":\"number\"}]}"))
                .andExpect(jsonPath("$.success").value(true));

        verify(async)
                .accept(
                        any(),
                        eq(key),
                        eq("a88d9f2415868ea3c1124ed4fcf00a8f00c0ee9df8cfb07632ef2278bd260f53"));
    }

    @Test
    void auditWriteFailureCannotTurnAcceptedRequestIntoFailure() throws Exception {
        doThrow(new IllegalStateException("audit offline")).when(logs).saveLog(any());

        create().andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").value("PROCESSING"));

        verify(async).accept(any(), eq(key), eq(FINGERPRINT));
        verifyNoInteractions(orders);
    }

    @Test
    void cancellationIsAuditedWithoutRequestBodyOrToken() throws Exception {
        when(orders.cancelOrder("order-1")).thenReturn(Result.ok());
        mvc.perform(put("/booking/orders/order-1/cancel").header("authorization", "secret-token"))
                .andExpect(jsonPath("$.success").value(true));
        assertEquals("CANCEL_BOOKING", recorded.get(0).getOperation());
        assertEquals("order-1", recorded.get(0).getTargetId());
        assertFalse(cn.hutool.json.JSONUtil.toJsonStr(recorded).contains("secret-token"));
        verifyNoInteractions(redis, async);
    }

    @Test
    void adminCreationAndVerificationAreAuditedWithoutLeakingCode() throws Exception {
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
        verifyNoInteractions(redis, async);
    }

    @Test
    void rateBackendFailureDoesNotReachAdmissionAndStillCleansContext() throws Exception {
        doThrow(new BookingControlException("REDIS_UNAVAILABLE", "暂不可用"))
                .when(redis)
                .checkRate(anyLong(), anyLong(), anyString());

        create().andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(async, orders);
        assertEquals("REDIS_UNAVAILABLE", recorded.get(0).getReasonCode());
    }

    private org.springframework.test.web.servlet.ResultActions create() throws Exception {
        return mvc.perform(
                post("/booking/orders")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body));
    }

    private BookingSubmissionDTO processing() {
        BookingSubmissionDTO result = new BookingSubmissionDTO();
        result.setRequestId(REQUEST_ID);
        result.setState("PROCESSING");
        result.setMessage("请求已受理，正在确认预约");
        return result;
    }

    private BookingSubmissionDTO transaction(
            boolean fail, boolean failAfterCommit, boolean uncertainCommit) {
        TestTransactionManager manager = new TestTransactionManager();
        manager.uncertainCommit = uncertainCommit;
        return new TransactionTemplate(manager)
                .execute(
                        status -> {
                            BookingOperationContext.trackTransaction();
                            if (failAfterCommit) {
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCommit() {
                                                throw new IllegalStateException(
                                                        "commit callback failed");
                                            }
                                        });
                            }
                            if (fail) {
                                throw new IllegalStateException("admission failed");
                            }
                            return processing();
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
