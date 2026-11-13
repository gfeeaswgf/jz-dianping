package com.hmdp.ratelimit;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.ProtectionInvalidRequestException;
import com.hmdp.utils.ProtectionKeys;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Component;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.hmdp.utils.RedisConstants.*;

/** 从服务端身份、已绑定参数和可信 IP 生成规则，不能靠更换 Token 绕过用户额度。 */

@Component
public class RateLimitPolicyResolver {

    private final ProtectionProperties p;
    private final ProtectionKeys keys;
    private final ClientIpResolver ips;

    // 构造方法注入
    public RateLimitPolicyResolver(ProtectionProperties p,ProtectionKeys keys,ClientIpResolver ips) {
        this.p = p;
        this.keys = keys;
        this.ips = ips;
    }

    /**
     * 根据限流策略生成限流规则
     * @param policy 限流策略
     * @param args 方法参数
     * @param request HTTP请求
     * @return 限流规则集合
     */
    public List<RateRule> resolve(RateLimitPolicy policy,Object[] args,HttpServletRequest request) {

        // 1. 判断是否为短信发送限流
        if (policy == RateLimitPolicy.SMS_SEND_IP) {
            // 1.1 获取客户端IP
            String ip = ips.resolve(request);

            // 1.2 生成Redis Key
            String key = keys.key(RATE_SMS_IP_KEY, ip);

            // 1.3 创建限流规则
            RateRule rule = RateRule.fixedWindow(key,p.getSmsIpLimit(),p.getIpWindowMs());

            // 1.4 返回限流规则
            return Collections.singletonList(rule);
        }

        // 2. 判断是否为登录限流
        if (policy == RateLimitPolicy.LOGIN_IP) {
            // 2.1 获取客户端IP
            String ip = ips.resolve(request);

            // 2.2 生成Redis Key
            String key = keys.key(RATE_LOGIN_IP_KEY, ip);

            // 2.3 创建限流规则
            RateRule rule = RateRule.fixedWindow(
                    key,
                    p.getLoginIpLimit(),
                    p.getIpWindowMs()
            );

            // 2.4 返回限流规则
            return Collections.singletonList(rule);
        }

        // 3. 获取当前登录用户
        UserDTO user = UserHolder.getUser();

        // 4. 校验限流策略、用户信息和请求参数
        if (policy != RateLimitPolicy.SECKILL
                || user == null
                || user.getId() == null
                || user.getId() <= 0
                || args == null
                || args.length == 0
                || !(args[0] instanceof Long)
                || (Long) args[0] <= 0) {
            throw new ProtectionInvalidRequestException();
        }

        // 5. 获取用户ID和优惠券ID
        String userId = user.getId().toString();
        String voucherId = args[0].toString();

        // 6. 创建优惠券维度的限流规则
        String voucherKey = keys.key(RATE_SECKILL_VOUCHER_KEY,voucherId);

        RateRule voucherRule = RateRule.fixedWindow(
                voucherKey,
                p.getSeckillVoucherLimit(),
                p.getSeckillVoucherWindowMs()
        );

        // 7. 创建用户维度的限流规则
        String userKey = keys.key(
                RATE_SECKILL_USER_KEY,
                userId
        );

        RateRule userRule = RateRule.fixedWindow(
                userKey,
                p.getSeckillUserLimit(),
                p.getSeckillUserWindowMs()
        );

        // 8. 创建用户与优惠券维度的冷却规则
        String pairKey = keys.key(
                RATE_SECKILL_PAIR_KEY,
                userId,
                voucherId
        );

        RateRule cooldownRule = RateRule.cooldown(
                pairKey,
                p.getSeckillUserVoucherCooldownMs()
        );

        // 9. 返回所有限流规则
        return Arrays.asList(
                voucherRule,
                userRule,
                cooldownRule
        );
    }
}
