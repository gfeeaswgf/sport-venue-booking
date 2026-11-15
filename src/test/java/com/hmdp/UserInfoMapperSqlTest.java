package com.hmdp;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserInfoMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 检查实际生成的 SQL，不连接数据库，防止手动主键被 ORM 的自增配置忽略。 */
class UserInfoMapperSqlTest {
    @Test
    void insertBindsTheExistingAccountId() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(UserInfoMapper.class);
        UserInfo info = new UserInfo();
        info.setUserId(7L);
        info.setRealName("测试同学");
        info.setStudentNo("2026007");

        BoundSql sql =
                configuration
                        .getMappedStatement(UserInfoMapper.class.getName() + ".insert")
                        .getBoundSql(info);

        assertTrue(
                sql.getSql().contains("user_id"),
                "资料 INSERT 必须包含当前账号的 user_id，实际 SQL: " + sql.getSql());
        ParameterMapping idParameter =
                sql.getParameterMappings().stream()
                        .filter(parameter -> "userId".equals(parameter.getProperty()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("INSERT 缺少 userId 绑定参数"));
        assertEquals(7L, configuration.newMetaObject(info).getValue(idParameter.getProperty()));
    }
}
