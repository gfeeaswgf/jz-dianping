package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.hmdp.ratelimit.RateLimit;
import com.hmdp.ratelimit.RateLimitPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 用户认证与签到入口，发送和登录采用 IP 准入，退出保持幂等。
 * </p>
 *
 * @author 虎哥
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     * @param phone 手机号
     * @param session 原会话参数，不用于保存验证码
     * @return 发送入口处理结果
     */
    @PostMapping("code")
    @RateLimit(policy = RateLimitPolicy.SMS_SEND_IP)
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session 原会话参数
     * @return 当前实现的验证码登录结果
     */
    @PostMapping("/login")
    @RateLimit(policy = RateLimitPolicy.LOGIN_IP)
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    /**
     * 登出功能
     * @param token 原始 authorization 头，缺失或已退出幂等成功
     * @return 当前凭证删除结果
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token){
        return userService.logout(token);
    }

    /**

     * @return 已由拦截器恢复的当前用户身份

     */
    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /**

     * @param userId 用户 ID

     * @return 原用户详情结果

     */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /**

     * @param userId 用户 ID

     * @return 公开的用户 DTO，不返回手机号或密码

     */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    /**

     * @return 当前用户当天签到结果

     */
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    /**

     * @return 当前用户本月连续签到天数

     */
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }
}
