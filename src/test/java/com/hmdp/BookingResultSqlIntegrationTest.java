package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;

import com.hmdp.entity.BookingRequestTask;
import com.hmdp.mapper.BookingRequestMapper;
import com.hmdp.mapper.BookingResultMapper;
import com.hmdp.utils.BookingRules;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 只允许连接本任务创建的13316独立实例，校验datadir后才建立测试结构。 */
@EnabledIfSystemProperty(named = "booking.result-sql-integration", matches = "true")
class BookingResultSqlIntegrationTest {

    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static DriverManagerDataSource dataSource;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void setupDatabase() throws Exception {
        dataSource =
                new DriverManagerDataSource(
                        "jdbc:mysql://127.0.0.1:13316/?useSSL=false&characterEncoding=utf8",
                        "root",
                        "");
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            try (ResultSet result = sql.executeQuery("SELECT @@datadir")) {
                result.next();
                String directory = result.getString(1).replace('\\', '/').toLowerCase();
                assertTrue(
                        directory.contains(
                                "/sport-venue-booking/backups/result-events-work/mysql-data"));
            }
            sql.execute("CREATE DATABASE IF NOT EXISTS hmdp");
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/async-booking-request.sql"));
            boolean migrated;
            try (ResultSet columns =
                    connection
                            .getMetaData()
                            .getColumns("hmdp", null, "tb_booking_request", "result_event_state")) {
                migrated = columns.next();
            }
            if (!migrated) {
                ScriptUtils.executeSqlScript(
                        connection, new ClassPathResource("db/booking-result-events.sql"));
            }
            sql.execute("CREATE TABLE IF NOT EXISTS test_orders(id VARCHAR(32) PRIMARY KEY)");
        }
        dataSource.setUrl("jdbc:mysql://127.0.0.1:13316/hmdp?useSSL=false&characterEncoding=utf8");
        Configuration configuration =
                new Configuration(
                        new Environment("isolated", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BookingRequestMapper.class);
        new XMLMapperBuilder(
                        new ClassPathResource("mapper/BookingResultMapper.xml").getInputStream(),
                        configuration,
                        "mapper/BookingResultMapper.xml",
                        configuration.getSqlFragments())
                .parse();
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void insertRequest() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            sql.executeUpdate("DELETE FROM tb_booking_request");
            sql.executeUpdate("DELETE FROM test_orders");
            sql.executeUpdate(
                    "INSERT INTO tb_booking_request(id,user_id,idempotency_key,request_hash,"
                        + "venue_id,court_id,slot_id,booking_date,active_date,real_name,student_no,phone,companions_json,status,next_publish_time,expire_time,create_time,update_time)"
                        + " VALUES ('"
                            + ID
                            + "',7,'key','hash',1,2,3,'2030-01-01','2030-01-01','test','test','test',"
                            + "'[]','ACCEPTED','2000-01-01','2030-01-01',NOW(),NOW())");
        }
    }

    @Test
    void coreRollbackDoesNotLeaveOrderOrResultEvent() throws Exception {
        try (SqlSession session = sessions.openSession(false)) {
            session.getConnection()
                    .createStatement()
                    .executeUpdate("INSERT INTO test_orders VALUES('" + ID + "')");
            assertEquals(
                    1,
                    session.getMapper(BookingRequestMapper.class).succeed(ID, BookingRules.now()));
            session.rollback();
        }
        assertEquals("ACCEPTED", snapshot().getStatus());
        assertEquals("NONE", snapshot().getResultEventState());
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM test_orders")) {
            rows.next();
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void independentConcurrentConfirmationsAndCancellationConverge() throws Exception {
        succeed();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> audit =
                    executor.submit(
                            () -> {
                                await(start);
                                try (SqlSession session = sessions.openSession(true)) {
                                    assertEquals(
                                            1,
                                            session.getMapper(BookingResultMapper.class)
                                                    .finishAudit(ID, "DONE", utc().plusMinutes(5)));
                                }
                            });
            Future<?> stats =
                    executor.submit(
                            () -> {
                                await(start);
                                try (SqlSession session = sessions.openSession(true)) {
                                    assertEquals(
                                            1,
                                            session.getMapper(BookingResultMapper.class)
                                                    .finishStats(ID, "DONE", utc().plusMinutes(5)));
                                }
                            });
            Future<?> cancel =
                    executor.submit(
                            () -> {
                                await(start);
                                try (SqlSession session = sessions.openSession(true)) {
                                    session.getMapper(BookingRequestMapper.class)
                                            .releaseDate(ID, 7L, BookingRules.now());
                                }
                            });
            start.countDown();
            audit.get();
            stats.get();
            cancel.get();
            BookingRequestTask result = snapshot();
            assertEquals("COMPLETED", result.getResultEventState());
            assertEquals("DONE", result.getAuditState());
            assertEquals("DONE", result.getStatsState());
            assertNull(result.getActiveDate());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lateSenderCannotReopenCompletedEventAndOldRequestClaimCannotDelayIt() {
        succeed();
        try (SqlSession session = sessions.openSession(true)) {
            BookingRequestMapper requests = session.getMapper(BookingRequestMapper.class);
            BookingResultMapper results = session.getMapper(BookingResultMapper.class);
            assertEquals(
                    0,
                    requests.claim(
                            ID,
                            BookingRules.now().plusSeconds(1),
                            BookingRules.now().plusSeconds(30)));
            assertEquals(
                    1,
                    results.claim(
                            ID,
                            "old-token",
                            BookingRules.now().plusSeconds(1),
                            BookingRules.now().plusSeconds(30)));
            assertEquals(1, results.finishAudit(ID, "DONE", utc().plusMinutes(5)));
            assertEquals(1, results.finishStats(ID, "DONE", utc().plusMinutes(5)));
            assertEquals(0, results.published(ID, "old-token", BookingRules.now()));
        }
        assertEquals("COMPLETED", snapshot().getResultEventState());
    }

    @Test
    void confirmationUsesDatabaseClockAndDoesNotWaitForPublisherToExpire() {
        succeed();
        try (SqlSession session = sessions.openSession(true)) {
            BookingResultMapper results = session.getMapper(BookingResultMapper.class);
            assertEquals(0, results.finishAudit(ID, "DONE", utc().minusSeconds(2)));
            assertEquals(1, results.finishAudit(ID, "EXPIRED", utc().minusSeconds(2)));
            assertEquals(0, results.finishAudit(ID, "DONE", utc().plusDays(1)));
        }
        assertEquals("EXPIRED", snapshot().getAuditState());
        assertEquals("PENDING", snapshot().getStatsState());
    }

    private void succeed() {
        try (SqlSession session = sessions.openSession(true)) {
            assertEquals(
                    1,
                    session.getMapper(BookingRequestMapper.class).succeed(ID, BookingRules.now()));
        }
    }

    private BookingRequestTask snapshot() {
        try (SqlSession session = sessions.openSession(true)) {
            return session.getMapper(BookingResultMapper.class).snapshot(ID);
        }
    }

    private static LocalDateTime utc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
