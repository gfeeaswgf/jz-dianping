package com.hmdp.ratelimit;

import com.hmdp.exception.ProtectionInvalidRequestException;
import com.hmdp.exception.ProtectionUnavailableException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;


@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redis;

    // 1. 创建Lua脚本对象
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();

    // 2. 加载Lua脚本
    static {
        SCRIPT.setLocation(new ClassPathResource("lua/protection/rate_limit.lua"));
        SCRIPT.setResultType(List.class);
    }

    // 构造方法注入
    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 执行Redis限流判断
     * @param rules 限流规则集合
     * @return 限流结果
     */
    public RateLimitDecision acquire(List<RateRule> rules) {

        // 1. 判断限流规则是否合法
        if (rules == null || rules.isEmpty() || rules.size() > 8) {
            throw new ProtectionInvalidRequestException();
        }

        // 2. 创建Redis Key集合和Lua脚本参数集合
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();

        // 3. 遍历所有限流规则
        for (RateRule rule : rules) {

            // 3.1 判断规则是否为空或者Key重复
            if (rule == null || keys.contains(rule.getKey())) {
                throw new ProtectionInvalidRequestException();
            }

            // 3.2 添加Redis Key
            keys.add(rule.getKey());

            // 3.3 添加限流规则类型
            args.add(rule.getKind().name());

            // 3.4 添加限流次数
            args.add(String.valueOf(rule.getLimit()));

            // 3.5 添加限流时间窗口
            args.add(String.valueOf(rule.getDurationMs()));
        }

        // 4. 执行Lua脚本
        List<?> result;

        try {
            result = redis.execute(SCRIPT, keys, args.toArray());
        } catch (RuntimeException e) {
            // Redis执行异常，拒绝请求
            throw new ProtectionUnavailableException("RATE_REDIS_FAILURE");
        }

        // 5. 校验Lua脚本返回结果
        if (result == null
                || result.size() != 2
                || !(result.get(0) instanceof Long)
                || !(result.get(1) instanceof Long)) {
            throw new ProtectionUnavailableException("RATE_REPLY_INVALID");
        }

        // 6. 获取Lua脚本返回的数据
        long status = (Long) result.get(0);
        long wait = (Long) result.get(1);

        // 7. 判断是否允许请求
        if (status == 0 && wait == 0) {
            // 7.1 限流通过，允许执行原业务
            return new RateLimitDecision(true, 0);
        }

        // 8. 判断是否触发限流
        if (status == 1 && wait > 0 && wait <= 86400000L) {
            // 8.1 限流失败，返回建议等待时间
            return new RateLimitDecision(false, wait);
        }

        // 9. 其他情况说明Lua脚本返回状态异常
        throw new ProtectionUnavailableException("RATE_STATE_INVALID");
    }
}

