package com.hmdp.controller;

import com.hmdp.dto.BookingProfileDTO;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/** 账号登录、退出及当前用户预约资料接口。 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    /** 发送手机验证码 */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone);
    }

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm) {
        // 实现登录功能
        return userService.login(loginForm);
    }

    /**
     * 登出功能
     *
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader("authorization") String token) {
        return userService.logout(token);
    }

    @GetMapping("/profile")
    public Result profile() {
        return userService.queryProfile();
    }

    @PutMapping("/profile")
    public Result updateProfile(@RequestBody BookingProfileDTO profile) {
        return userService.updateProfile(profile);
    }

    @GetMapping("/me")
    public Result me() {
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }
}
