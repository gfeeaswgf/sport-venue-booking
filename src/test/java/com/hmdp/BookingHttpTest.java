package com.hmdp;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.UserController;
import com.hmdp.dto.BookingProfileDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IUserService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingHttpTest {
    MockMvc mvc;
    IUserService users;

    @BeforeEach
    void setup() {
        UserController controller = new UserController();
        users = mock(IUserService.class);
        ReflectionTestUtils.setField(controller, "userService", users);
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new WebExceptionAdvice())
                        .addInterceptors(new LoginInterceptor())
                        .build();
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void privateProfileRouteReturnsExpectedFieldNames() throws Exception {
        BookingProfileDTO p = new BookingProfileDTO();
        p.setRealName("本人姓名");
        p.setStudentNo("2026007");
        p.setPhone("13800000007");
        p.setIsAdmin(false);
        when(users.queryProfile()).thenReturn(Result.ok(p));
        mvc.perform(get("/user/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.realName").value("本人姓名"))
                .andExpect(jsonPath("$.data.isAdmin").value(false));
    }

    @Test
    void removedSocialAndSignInRoutesAreNotExposed() throws Exception {
        mvc.perform(get("/user/info/99")).andExpect(status().isNotFound());
        mvc.perform(get("/user/99")).andExpect(status().isNotFound());
        mvc.perform(post("/user/sign")).andExpect(status().isNotFound());
        mvc.perform(get("/user/sign/count")).andExpect(status().isNotFound());
    }

    @Test
    void profileRequiresAuthenticatedIdentity() throws Exception {
        UserHolder.removeUser();
        mvc.perform(get("/user/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidProfileBodyHasUserFacingError() throws Exception {
        mvc.perform(put("/user/profile").contentType("application/json").content("{broken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").isNotEmpty());
    }
}
