package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.BookingProfileDTO;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

/** 账号与当前用户预约资料服务。 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);

    Result queryProfile();

    Result updateProfile(BookingProfileDTO profile);

    Result logout(String token);
}
