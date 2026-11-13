package com.hmdp.auth;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.exception.*;
import com.hmdp.utils.ProtectionKeys;
import com.hmdp.utils.RegexUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.*;
import static com.hmdp.utils.RedisConstants.*;

/** 协调验证码状态与短信结果，Lua 负责状态原子性，外部通道不属于 Redis 事务。 */
@Component
public class SmsCodeService {
    private final StringRedisTemplate redis;
    private final ProtectionProperties p;
    private final ProtectionKeys keys;
    private final SmsSender sender;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final DefaultRedisScript<List> RESERVE=script("sms_reserve",List.class);
    private static final DefaultRedisScript<Long> CLEANUP=script("sms_cleanup",Long.class);
    private static final DefaultRedisScript<List> VERIFY=script("sms_verify",List.class);
    /**
     * @param redis 单实例客户端
     * @param p 规则配置
     * @param keys 命名空间
     * @param sender 短信模拟端口
     */
    public SmsCodeService(StringRedisTemplate redis,ProtectionProperties p,ProtectionKeys keys,SmsSender sender) {
        this.redis=redis;this.p=p;this.keys=keys;this.sender=sender;
    }
    /**
     * 原子预占发送额度后调用通道一次；明确失败仅清理本次码，不返配额或释放冷却。
     * @param phone 符合原手机号规则的号码
     * @throws ProtectionRejectedException 冷却、配额或锁定拒绝
     * @throws ProtectionUnavailableException Redis 或通道故障，未知结果保留验证码
     * @throws ProtectionInvalidRequestException 手机号非法
     */
    public void sendCode(String phone) {
        if(RegexUtils.isPhoneInvalid(phone)) throw new ProtectionInvalidRequestException();
        String code=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1000000));
        String issue=UUID.randomUUID().toString().replace("-","");
        List<String> stateKeys=Arrays.asList(keys.key(LOGIN_CODE_KEY,phone),keys.key(SMS_ISSUE_KEY,phone),keys.key(SMS_COOLDOWN_KEY,phone),
                keys.key(SMS_HOUR_KEY,phone),keys.key(SMS_DAY_KEY,phone),keys.key(VERIFY_LOCK_KEY,phone));
        List<Long> result=execute(RESERVE,stateKeys,code,issue,String.valueOf(p.getSmsCodeTtlMs()),String.valueOf(p.getSmsCooldownMs()),String.valueOf(p.getSmsHourLimit()),String.valueOf(p.getSmsDayLimit()));
        if(result.get(0)==1L) throw new ProtectionRejectedException(result.get(1));
        if(result.get(0)!=0L) throw new ProtectionUnavailableException("SMS_RESERVE_STATE");
        SmsSendOutcome outcome;
        try { outcome=sender.send(phone,code,issue); }
        catch(RuntimeException e) { outcome=SmsSendOutcome.UNKNOWN; }
        if(outcome==SmsSendOutcome.ACCEPTED) return;
        if(outcome==SmsSendOutcome.REJECTED) {
            // 只清理拥有相同签发 ID 的码，旧调用不能删掉后来重发的新码。
            Long cleaned;
            try { cleaned=redis.execute(CLEANUP,stateKeys.subList(0,2),issue); }
            catch(RuntimeException e) { throw new ProtectionUnavailableException("SMS_CLEANUP_FAILURE"); }
            if(cleaned==null || (cleaned!=0L && cleaned!=1L)) throw new ProtectionUnavailableException("SMS_CLEANUP_STATE");
        }
        throw new ProtectionUnavailableException("SMS_DELIVERY_UNAVAILABLE");
    }

    /**
     * 在同一次 Lua 中比较、计错并成功消费，避免并发请求同时通过 Java GET/DEL。
     * @param phone 手机号
     * @param code 六位数字验证码
     * @return 仅首次正确消费为 true；错误、缺失、过期、已消费统一为 false
     * @throws ProtectionRejectedException 手机号锁定时抛出
     * @throws ProtectionUnavailableException Redis 或状态异常时抛出
     * @throws ProtectionInvalidRequestException 格式非法时抛出
     */
    public boolean verifyAndConsume(String phone,String code) {
        if(RegexUtils.isPhoneInvalid(phone) || code==null || !code.matches("[0-9]{6}")) throw new ProtectionInvalidRequestException();
        List<Long> result=execute(VERIFY,Arrays.asList(keys.key(LOGIN_CODE_KEY,phone),keys.key(SMS_ISSUE_KEY,phone),keys.key(VERIFY_FAIL_KEY,phone),keys.key(VERIFY_LOCK_KEY,phone)),
                code,String.valueOf(p.getVerifyFailureLimit()),String.valueOf(p.getVerifyFailureWindowMs()),String.valueOf(p.getVerifyLockMs()));
        if(result.get(0)==1L) throw new ProtectionRejectedException(result.get(1));
        return result.get(0)==0L;
    }
    @SuppressWarnings("unchecked")
    private List<Long> execute(DefaultRedisScript<List> script,List<String> stateKeys,Object... args) {
        List<?> reply;
        try { reply=redis.execute(script,stateKeys,args); }
        catch(RuntimeException e) { throw new ProtectionUnavailableException("SMS_REDIS_FAILURE"); }
        if(reply==null || reply.size()!=2 || !(reply.get(0) instanceof Long) || !(reply.get(1) instanceof Long)) throw new ProtectionUnavailableException("SMS_REPLY_INVALID");
        long status=(Long)reply.get(0),wait=(Long)reply.get(1);
        if(!((status==0 || status==2) && wait==0) && !(status==1 && wait>0 && wait<=86400000L)) throw new ProtectionUnavailableException("SMS_STATE_INVALID");
        return (List<Long>)reply;
    }
    private static <T> DefaultRedisScript<T> script(String name,Class<T> type) {
        DefaultRedisScript<T> s=new DefaultRedisScript<>();s.setLocation(new ClassPathResource("lua/protection/"+name+".lua"));s.setResultType(type);return s;
    }
}
