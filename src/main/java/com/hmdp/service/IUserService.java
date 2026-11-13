package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 用户认证、退出与签到服务契约。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**

     * @param token 当前原始 Token

     * @return 幂等退出结果，Redis 失败时抛安全系统异常

     */
    Result logout(String token);

    /**

     * @param phone 手机号

     * @param session 保留接口参数

     * @return 模拟通道接受结果

     */
    Result sendCode(String phone, HttpSession session);

    /**

     * @param loginForm 登录参数

     * @param session 保留接口参数

     * @return Token 或统一验证码失败结果

     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**

     * @return 当天签到结果

     */
    Result sign();

    /**

     * @return 本月连续签到天数

     */
    Result signCount();

}
