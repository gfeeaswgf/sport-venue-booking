package com.hmdp;

import com.hmdp.dto.BookingProfileDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.BookingAdminAccess;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingProfileTest {
    UserServiceImpl service;
    UserMapper users;
    IUserInfoService infos;

    @BeforeEach
    void setup() {
        service = new UserServiceImpl();
        users = mock(UserMapper.class);
        infos = mock(IUserInfoService.class);
        ReflectionTestUtils.setField(service, "baseMapper", users);
        ReflectionTestUtils.setField(service, "userInfoService", infos);
        ReflectionTestUtils.setField(service, "bookingAdminAccess", new BookingAdminAccess());
        UserDTO login = new UserDTO();
        login.setId(7L);
        UserHolder.saveUser(login);
        User user = new User();
        user.setId(7L);
        user.setPhone("13800000007");
        user.setNickName("原昵称");
        when(users.selectById(7L)).thenReturn(user);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void profileUsesCurrentAccountAndFreshDatabaseInfo() {
        UserInfo info = new UserInfo();
        info.setUserId(7L);
        info.setRealName("张同学");
        info.setStudentNo("2026007");
        when(infos.getById(7L)).thenReturn(info);
        BookingProfileDTO data = (BookingProfileDTO) service.queryProfile().getData();
        assertEquals("张同学", data.getRealName());
        assertEquals("2026007", data.getStudentNo());
        assertEquals("13800000007", data.getPhone());
        assertFalse(data.getIsAdmin());
        info.setRealName("修改后姓名");
        assertEquals("修改后姓名", ((BookingProfileDTO) service.queryProfile().getData()).getRealName());
    }

    @Test
    void profileSaveCannotChangeAccountOrPrivilege() {
        BookingProfileDTO input = new BookingProfileDTO();
        input.setId(999L);
        input.setPhone("13900000000");
        input.setIsAdmin(true);
        input.setRealName(" 王同学 ");
        input.setStudentNo(" 2026007 ");
        when(infos.save(any(UserInfo.class))).thenReturn(true);
        service.updateProfile(input);
        org.mockito.ArgumentCaptor<UserInfo> saved =
                org.mockito.ArgumentCaptor.forClass(UserInfo.class);
        verify(infos).save(saved.capture());
        assertEquals(7L, saved.getValue().getUserId());
        assertEquals("王同学", saved.getValue().getRealName());
        assertEquals("2026007", saved.getValue().getStudentNo());
        verify(users, never()).updateById(any());
    }

    @Test
    void anonymousProfileIsRejected() {
        UserHolder.removeUser();
        assertThrows(BookingException.class, () -> service.queryProfile());
    }
}
