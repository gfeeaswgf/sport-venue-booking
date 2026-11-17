package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.hmdp.entity.VenueSlot;
import com.hmdp.mapper.VenueCourtMapper;
import com.hmdp.mapper.VenueMapper;
import com.hmdp.mapper.VenueSlotMapper;
import com.hmdp.service.impl.VenueServiceImpl;
import com.hmdp.utils.BookingCache;
import com.hmdp.utils.BookingException;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅操作已校验私有datadir的13316测试库，不读取业务连接配置。 */
@EnabledIfSystemProperty(named = "booking.result-sql-integration", matches = "true")
class BookingRollingSlotsSqlIntegrationTest {

    private static final long VENUE = 999001L;
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 7, 0);
    private static final SqlCounter COUNTER = new SqlCounter();
    private static DriverManagerDataSource dataSource;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void setupDatabase() throws Exception {
        dataSource =
                new DriverManagerDataSource(
                        "jdbc:mysql://127.0.0.1:13316/?useSSL=false&characterEncoding=utf8&socketTimeout=8000",
                        "root",
                        "");
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            try (ResultSet result = sql.executeQuery("SELECT @@datadir")) {
                result.next();
                assertTrue(
                        result.getString(1)
                                .replace('\\', '/')
                                .toLowerCase()
                                .contains(
                                        "/sport-venue-booking/backups/result-events-work/mysql-data"));
            }
            sql.execute("CREATE DATABASE IF NOT EXISTS hmdp");
            sql.execute("USE hmdp");
            String source =
                    StreamUtils.copyToString(
                            new ClassPathResource("db/sport-booking.sql").getInputStream(),
                            StandardCharsets.UTF_8);
            String schema =
                    source.substring(
                            source.indexOf("CREATE TABLE IF NOT EXISTS tb_venue ("),
                            source.indexOf("-- 以下为可保留"));
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8));
        }
        dataSource.setUrl(
                "jdbc:mysql://127.0.0.1:13316/hmdp?useSSL=false&characterEncoding=utf8&socketTimeout=8000");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(
                new Environment("isolated-slots", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(COUNTER);
        configuration.addMapper(VenueMapper.class);
        configuration.addMapper(VenueCourtMapper.class);
        configuration.addMapper(VenueSlotMapper.class);
        new XMLMapperBuilder(
                        new ClassPathResource("mapper/VenueSlotMapper.xml").getInputStream(),
                        configuration,
                        "mapper/VenueSlotMapper.xml",
                        configuration.getSqlFragments())
                .parse();
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            sql.executeUpdate("DELETE FROM tb_booking_order");
            sql.executeUpdate("DELETE FROM tb_venue_slot");
            sql.executeUpdate("DELETE FROM tb_venue_court");
            sql.executeUpdate("DELETE FROM tb_venue");
            sql.executeUpdate(
                    "INSERT INTO tb_venue(id,name,category,address,open_hours,enabled,create_time) "
                            + "VALUES(999001,'test','BADMINTON','test','08:00-21:00',1,NOW())");
        }
        addCourts(1);
        COUNTER.reads.set(0);
        COUNTER.writes.set(0);
    }

    @Test
    void actualMultiValuesWrites273RowsInTwoStatementsAndRepeatingWritesNothing() throws Exception {
        addCourts(2);
        assertEquals(273, generate());
        assertEquals(1, COUNTER.reads.get());
        assertEquals(2, COUNTER.writes.get());
        assertEquals(273, slotCount());
        COUNTER.reads.set(0);
        COUNTER.writes.set(0);
        assertEquals(0, generate());
        assertEquals(1, COUNTER.reads.get());
        assertEquals(0, COUNTER.writes.get());
    }

    @Test
    void twoTransactionsStartingTogetherDoNotDuplicateSlots() throws Exception {
        addCourts(1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> action =
                    () -> {
                        start.await();
                        return generate();
                    };
            Future<Integer> first = executor.submit(action);
            Future<Integer> second = executor.submit(action);
            start.countDown();
            int left = first.get(12, TimeUnit.SECONDS);
            int right = second.get(12, TimeUnit.SECONDS);
            assertEquals(182, left + right);
            assertTrue(left == 0 || right == 0);
            assertEquals(182, slotCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void laterInvalidCourtRollsBackAlreadyFlushedBatch() throws Exception {
        addCourts(3);
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            sql.executeUpdate(
                    "UPDATE tb_venue_court SET capacity=0 WHERE id=(SELECT last_id FROM "
                            + "(SELECT MAX(id) last_id FROM tb_venue_court) t)");
        }
        assertThrows(BookingException.class, this::generate);
        assertTrue(COUNTER.writes.get() >= 1, "确实写出过一批，才能证明后续异常回滚");
        assertEquals(0, slotCount());
    }

    @Test
    void windowReadUsesDateRangeEvenWhenOldHistoryAccumulates() throws Exception {
        addCourts(1);
        List<Long> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery("SELECT id FROM tb_venue_court ORDER BY id")) {
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
        }
        List<VenueSlot> history = new ArrayList<>();
        for (Long id : ids) {
            for (int day = 0; day < 365; day++) {
                history.add(
                        new VenueSlot()
                                .setCourtId(id)
                                .setBookingDate(LocalDate.of(2020, 1, 1).plusDays(day))
                                .setStartTime(LocalTime.of(8, 0))
                                .setEndTime(LocalTime.of(9, 0))
                                .setCapacity(1)
                                .setBookedCount(0)
                                .setEnabled(true)
                                .setCreateTime(NOW));
            }
        }
        try (SqlSession session = sessions.openSession(false)) {
            assertEquals(730, session.getMapper(VenueSlotMapper.class).insertBatch(history));
            session.commit();
        }
        assertEquals(182, generate());
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("courtIds", ids);
        parameters.put("firstDate", NOW.toLocalDate());
        parameters.put("lastDate", NOW.toLocalDate().plusDays(6));
        MappedStatement mapped =
                sessions.getConfiguration()
                        .getMappedStatement("com.hmdp.mapper.VenueSlotMapper.findWindowForCourts");
        BoundSql bound = mapped.getBoundSql(parameters);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement sql = connection.prepareStatement("EXPLAIN " + bound.getSql())) {
            new DefaultParameterHandler(mapped, parameters, bound).setParameters(sql);
            try (ResultSet plan = sql.executeQuery()) {
                boolean checked = false;
                while (plan.next()) {
                    if ("s".equals(plan.getString("table"))
                            || "tb_venue_slot".equals(plan.getString("table"))) {
                        assertEquals("range", plan.getString("type"));
                        assertTrue(Integer.parseInt(plan.getString("key_len")) >= 11);
                        checked = true;
                    }
                }
                assertTrue(checked);
            }
        }
    }

    @Test
    void closedBookedSlotIsNotOverwrittenByBatch() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            sql.executeUpdate(
                    "INSERT INTO"
                        + " tb_venue_slot(court_id,booking_date,start_time,end_time,capacity,booked_count,enabled,create_time)"
                        + " SELECT id,'2030-01-01','08:00:00','09:00:00',1,1,0,NOW() FROM"
                        + " tb_venue_court LIMIT 1");
        }
        assertEquals(90, generate());
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement();
                ResultSet result =
                        sql.executeQuery(
                                "SELECT booked_count,enabled FROM tb_venue_slot WHERE"
                                        + " booking_date='2030-01-01' AND start_time='08:00:00'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt("booked_count"));
            assertEquals(0, result.getInt("enabled"));
        }
    }

    private int generate() {
        try (SqlSession session = sessions.openSession(false)) {
            VenueServiceImpl service = new VenueServiceImpl();
            ReflectionTestUtils.setField(
                    service, "baseMapper", session.getMapper(VenueMapper.class));
            ReflectionTestUtils.setField(
                    service, "courtMapper", session.getMapper(VenueCourtMapper.class));
            ReflectionTestUtils.setField(
                    service, "slotMapper", session.getMapper(VenueSlotMapper.class));
            ReflectionTestUtils.setField(service, "bookingCache", mock(BookingCache.class));
            try {
                int count = service.ensureRollingSlots(VENUE, NOW);
                session.commit();
                return count;
            } catch (RuntimeException exception) {
                session.rollback();
                throw exception;
            }
        }
    }

    private void addCourts(int count) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            for (int index = 0; index < count; index++) {
                sql.executeUpdate(
                        "INSERT INTO"
                            + " tb_venue_court(venue_id,name,mode,capacity,max_people,enabled,create_time)"
                            + " VALUES(999001,'test','COURT',1,6,1,NOW())");
            }
        }
    }

    private int slotCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement();
                ResultSet result = sql.executeQuery("SELECT COUNT(*) FROM tb_venue_slot")) {
            result.next();
            return result.getInt(1);
        }
    }

    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    public static class SqlCounter implements Interceptor {

        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (id.endsWith("VenueSlotMapper.findWindowForCourts")) {
                reads.incrementAndGet();
            } else if (id.endsWith("VenueSlotMapper.insertBatch")) {
                writes.incrementAndGet();
            }
            return invocation.proceed();
        }
    }
}
