package com.hmdp.ratelimit;

import com.hmdp.exception.ProtectionInvalidRequestException;
import com.hmdp.exception.ProtectionUnavailableException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;

/** 把全部维度交给同一次 Lua 调用，避免逐维扣额造成部分成功。 */
@Component
public class RedisRateLimiter {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();
    static { SCRIPT.setLocation(new ClassPathResource("lua/protection/rate_limit.lua")); SCRIPT.setResultType(List.class); }
    /**
     * @param redis 当前单实例 Redis 客户端
     */
    public RedisRateLimiter(StringRedisTemplate redis) { this.redis=redis; }
    /**
     * 原子检查并扣除所有规则的额度，拒绝不部分扣额，异常不自动重放。
     * @param rules 一至八条不同 Key 的规则
     * @return 准入决定
     * @throws ProtectionInvalidRequestException 规则不合法时抛出
     * @throws ProtectionUnavailableException Redis 或脚本状态异常时抛出
     */
    public RateLimitDecision acquire(List<RateRule> rules) {
        if (rules == null || rules.isEmpty() || rules.size()>8) throw new ProtectionInvalidRequestException();
        List<String> keys=new ArrayList<>(); List<String> args=new ArrayList<>();
        for (RateRule rule : rules) {
            if(rule==null || keys.contains(rule.getKey())) throw new ProtectionInvalidRequestException();
            keys.add(rule.getKey()); args.add(rule.getKind().name()); args.add(String.valueOf(rule.getLimit())); args.add(String.valueOf(rule.getDurationMs()));
        }
        List<?> result;
        try { result=redis.execute(SCRIPT,keys,args.toArray()); }
        catch(RuntimeException e) { throw new ProtectionUnavailableException("RATE_REDIS_FAILURE"); }
        if(result==null || result.size()!=2 || !(result.get(0) instanceof Long) || !(result.get(1) instanceof Long)) throw new ProtectionUnavailableException("RATE_REPLY_INVALID");
        long status=(Long)result.get(0),wait=(Long)result.get(1);
        if(status==0 && wait==0) return new RateLimitDecision(true,0);
        if(status==1 && wait>0 && wait<=86400000L) return new RateLimitDecision(false,wait);
        throw new ProtectionUnavailableException("RATE_STATE_INVALID");
    }
}
