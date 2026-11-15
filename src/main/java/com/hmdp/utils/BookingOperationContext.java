package com.hmdp.utils;

import lombok.Data;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** 只保存本次请求的受控元数据，不保存请求体或登录 Token。 */
public final class BookingOperationContext {
    private static final ThreadLocal<State> LOCAL = new ThreadLocal<>();

    private BookingOperationContext() {}

    public static State current() {
        return LOCAL.get();
    }

    public static State open() {
        State state = new State();
        state.setRequestId(UUID.randomUUID().toString());
        LOCAL.set(state);
        return state;
    }

    public static void clear() {
        LOCAL.remove();
    }

    public static void trackTransaction() {
        State state = current();
        if (state == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        // 在事务开头登记，提交后的其他回调即使报错，也不会误认为数据库已回滚。
        state.setTransactionState("ACTIVE");
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        state.setTransactionState("COMMITTED");
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            state.setTransactionState("COMMITTED");
                        } else if (status == STATUS_ROLLED_BACK) {
                            state.setTransactionState("ROLLED_BACK");
                        } else if (!"COMMITTED".equals(state.getTransactionState())) {
                            state.setTransactionState("UNKNOWN");
                        }
                    }
                });
    }

    @Data
    public static class State {
        private String requestId;
        private String transactionState = "NONE";
        private String executionType = "EXECUTED";
        private String idempotencyKeyHash;
        private String targetId;
    }
}
