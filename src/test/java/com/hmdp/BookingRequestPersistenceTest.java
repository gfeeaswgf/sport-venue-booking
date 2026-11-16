package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.service.impl.BookingRequestPersistenceServiceImpl;
import com.hmdp.utils.BookingAdmissionRedis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实Spring事务完成回调，数据库网络仅替换为Mapper边界。 */
class BookingRequestPersistenceTest {
    BookingRequestPersistenceServiceImpl persistence;
    BookingRequestMapper requests;
    BookingAdmissionRedis admission;
    BookingRequestTask task;

    @BeforeEach
    void setup() {
        persistence = new BookingRequestPersistenceServiceImpl();
        requests = mock(BookingRequestMapper.class);
        admission = mock(BookingAdmissionRedis.class);
        ReflectionTestUtils.setField(persistence, "requests", requests);
        ReflectionTestUtils.setField(persistence, "admission", admission);
        task = new BookingRequestTask();
        when(requests.insert(task)).thenReturn(1);
    }

    @Test
    void successfulCommitDoesNotReleaseTheReservedPlace() {
        new TransactionTemplate(new Manager(false))
                .execute(
                        status -> {
                            persistence.save(task);
                            verifyNoInteractions(admission);
                            return null;
                        });
        verifyNoInteractions(admission);
    }

    @Test
    void knownRollbackReleasesOnlyAfterTransactionCompletion() {
        new TransactionTemplate(new Manager(false))
                .execute(
                        status -> {
                            persistence.save(task);
                            status.setRollbackOnly();
                            verifyNoInteractions(admission);
                            return null;
                        });
        verify(admission).release(task);
    }

    @Test
    void unknownCommitCannotReleaseARequestThatMayAlreadyExist() {
        assertThrows(
                TransactionSystemException.class,
                () ->
                        new TransactionTemplate(new Manager(true))
                                .execute(
                                        status -> {
                                            persistence.save(task);
                                            return null;
                                        }));
        verifyNoInteractions(admission);
    }

    static class Manager extends AbstractPlatformTransactionManager {
        final boolean unknown;

        Manager(boolean unknown) {
            this.unknown = unknown;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (unknown) {
                throw new TransactionSystemException("uncertain commit");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
