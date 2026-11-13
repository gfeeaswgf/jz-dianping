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
    /**
     * @param p 规则配置
     * @param keys Key 构造器
     * @param ips 客户端 IP 解析器
     */
    public RateLimitPolicyResolver(ProtectionProperties p,ProtectionKeys keys,ClientIpResolver ips) { this.p=p;this.keys=keys;this.ips=ips; }
    /**
     * 构造单次 Lua 所需的全部规则；券 ID 按参数位置读取，不依赖编译参数名。
     * @param policy 固定策略
     * @param args Controller 绑定的参数
     * @param request 当前请求
     * @return 一组不同维度规则
     * @throws ProtectionInvalidRequestException 策略、身份或参数无效时抛出
     */
    public List<RateRule> resolve(RateLimitPolicy policy,Object[] args,HttpServletRequest request) {
        if(policy==RateLimitPolicy.SMS_SEND_IP) return Collections.singletonList(RateRule.fixedWindow(keys.key(RATE_SMS_IP_KEY,ips.resolve(request)),p.getSmsIpLimit(),p.getIpWindowMs()));
        if(policy==RateLimitPolicy.LOGIN_IP) return Collections.singletonList(RateRule.fixedWindow(keys.key(RATE_LOGIN_IP_KEY,ips.resolve(request)),p.getLoginIpLimit(),p.getIpWindowMs()));
        UserDTO user=UserHolder.getUser();
        if(policy!=RateLimitPolicy.SECKILL || user==null || user.getId()==null || user.getId()<=0
                || args==null || args.length==0 || !(args[0] instanceof Long) || (Long)args[0]<=0) throw new ProtectionInvalidRequestException();
        String u=user.getId().toString(),v=args[0].toString();
        return Arrays.asList(RateRule.fixedWindow(keys.key(RATE_SECKILL_VOUCHER_KEY,v),p.getSeckillVoucherLimit(),p.getSeckillVoucherWindowMs()),
                RateRule.fixedWindow(keys.key(RATE_SECKILL_USER_KEY,u),p.getSeckillUserLimit(),p.getSeckillUserWindowMs()),
                RateRule.cooldown(keys.key(RATE_SECKILL_PAIR_KEY,u,v),p.getSeckillUserVoucherCooldownMs()));
    }
}
