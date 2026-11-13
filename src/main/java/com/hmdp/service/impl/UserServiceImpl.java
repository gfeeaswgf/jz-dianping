package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.hmdp.auth.SmsCodeService;
import com.hmdp.auth.TokenSessionService;
import com.hmdp.exception.ProtectionInvalidRequestException;
import com.hmdp.exception.ProtectionUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 用户认证与签到应用服务；验证码和 Token 状态由独立组件维护。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SmsCodeService smsCodeService;

    @Resource
    private TokenSessionService tokenSessionService;

    /**
     * 经专用组件完成原子签发及短信模拟发送，发送入口的 IP 限流由 Controller AOP 执行。
     * @param phone 手机号
     * @param session 原接口保留的会话参数，不用于保存验证码
     * @return 通道接受时返回成功
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        smsCodeService.sendCode(phone);
        return Result.ok();
    }

    /**
     * 先原子消费验证码再查询/注册用户；Redis 消费不与 MySQL 组成跨系统事务。
     * @param loginForm 登录参数
     * @param session 保留的原接口参数
     * @return 登录 Token 或统一验证码失败结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if (loginForm == null) throw new ProtectionInvalidRequestException();
        String phone = loginForm.getPhone();
        if (!smsCodeService.verifyAndConsume(phone, loginForm.getCode())) {
            return Result.fail("验证码错误或已失效");
        }
        User user;
        try {
            user = query().eq("phone", phone).one();
            if (user == null) user = createUserWithPhone(phone);
        } catch (RuntimeException e) {
            // 原始 SQL 异常可能带手机号，不能交给通用异常处理器输出异常链。
            log.warn("auth_database_failure type={}", e.getClass().getSimpleName());
            throw new ProtectionUnavailableException("AUTH_DATABASE_FAILURE");
        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(tokenSessionService.create(userDTO));
    }

    /**

     * @param token 当前原始 Token

     * @return 删除完成或凭证已不存在时成功

     */
    @Override
    public Result logout(String token) {
        tokenSessionService.logout(token);
        return Result.ok();
    }

    /**

     * @return 当天签到结果，使用原有月度 Bitmap 实现

     */
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**

     * @return 本月截至今天的连续签到天数

     */
    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
