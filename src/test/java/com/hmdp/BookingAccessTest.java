package com.hmdp;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.BookingAdminAccess;
import com.hmdp.utils.BookingAdminInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class BookingAccessTest {
    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void adminAccessDefaultsToDeniedAndCannotBeGuessed() {
        BookingAdminAccess access = new BookingAdminAccess();
        assertFalse(access.isAdmin(1L));
        ReflectionTestUtils.setField(access, "adminUserIds", " 7, 42 ");
        assertTrue(access.isAdmin(7L));
        assertFalse(access.isAdmin(77L));
        assertFalse(access.isAdmin(null));
    }

    @Test
    void adminRouteRejectsOrdinaryUserWithForbidden() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        BookingAdminInterceptor interceptor = new BookingAdminInterceptor(new BookingAdminAccess());
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void adminRouteRejectsAnonymousWithUnauthorized() throws Exception {
        BookingAdminInterceptor interceptor = new BookingAdminInterceptor(new BookingAdminAccess());
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), response, new Object()));
        assertEquals(401, response.getStatus());
    }
}
