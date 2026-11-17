package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisConstants;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

/** 旧业务删除后，验证码登录、自动注册和 Redis Token 仍可用。 */
class UserLoginTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void firstLoginInsertsAccountAndUsesDatabaseIdForToken() {
        UserServiceImpl service = new UserServiceImpl();
        UserMapper users = mock(UserMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ReflectionTestUtils.setField(service, "baseMapper", users);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.get(RedisConstants.LOGIN_CODE_KEY + "13812345678")).thenReturn("123456");
        when(users.insert(any(User.class)))
                .thenAnswer(
                        call -> {
                            ((User) call.getArgument(0)).setId(77L);
                            return 1;
                        });
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13812345678");
        form.setCode("123456");
        Result result = service.login(form);
        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).insert(saved.capture());
        assertEquals("13812345678", saved.getValue().getPhone());
        assertEquals(77L, saved.getValue().getId());
        ArgumentCaptor<Map> token = ArgumentCaptor.forClass(Map.class);
        verify(hashes)
                .putAll(eq(RedisConstants.LOGIN_USER_KEY + result.getData()), token.capture());
        assertEquals("77", token.getValue().get("id"));
        assertNotNull(token.getValue().get("nickName"));
        assertFalse(token.getValue().containsKey("phone"));
    }
}
