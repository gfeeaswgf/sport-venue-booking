package com.hmdp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 显式开启的本地 SQL 计划检查。只执行 EXPLAIN，绝不执行初始化 INSERT。 */
@EnabledIfSystemProperty(named = "booking.sql-verification", matches = "true")
class BookingSlotSeedSqlTest {
    @Test
    @SuppressWarnings("unchecked")
    void initializationStatementsHaveValidMysqlPlans() throws Exception {
        Map<String, Object> yaml;
        try (java.io.InputStream in =
                Files.newInputStream(Paths.get("src/main/resources/application.yaml"))) {
            yaml = new Yaml().load(in);
        }
        Map<String, Object> datasource =
                (Map<String, Object>) ((Map<String, Object>) yaml.get("spring")).get("datasource");
        try (Connection connection =
                        DriverManager.getConnection(
                                datasource.get("url").toString(),
                                datasource.get("username").toString(),
                                datasource.get("password").toString());
                Statement statement = connection.createStatement()) {
            // MySQL 不允许在只读事务中 EXPLAIN INSERT；这里只发 EXPLAIN 计划命令。
            statement.execute("SET NAMES utf8mb4");
            for (String file : Arrays.asList("sport-booking.sql", "sport-booking-slots.sql")) {
                String sql =
                        new String(
                                Files.readAllBytes(Paths.get("src/main/resources/db/" + file)),
                                StandardCharsets.UTF_8);
                Matcher inserts =
                        Pattern.compile("(?s)INSERT IGNORE INTO tb_venue_slot\\b.*?;").matcher(sql);
                int count = 0;
                while (inserts.find()) {
                    String plan = "EXPLAIN " + inserts.group();
                    assertDoesNotThrow(
                            () -> {
                                try (ResultSet ignored = statement.executeQuery(plan)) {}
                            },
                            file + " 的场次 SQL 必须能在本地 MySQL 生成执行计划");
                    count++;
                }
                assertEquals(2, count, "需要同时检查包场和游泳名额两个初始化语句");
            }
        }
    }
}
