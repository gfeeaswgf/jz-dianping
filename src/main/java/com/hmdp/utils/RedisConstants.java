package com.hmdp.utils;

/** Redis 业务前缀和原有 TTL 常量；保护模块时间配置使用明确单位。 */
public class RedisConstants {
    public static final String SMS_ISSUE_KEY = "login:sms:issue:";
    public static final String SMS_COOLDOWN_KEY = "login:sms:cooldown:";
    public static final String SMS_HOUR_KEY = "login:sms:hour:";
    public static final String SMS_DAY_KEY = "login:sms:day:";
    public static final String VERIFY_FAIL_KEY = "login:verify:fail:";
    public static final String VERIFY_LOCK_KEY = "login:verify:lock:";
    public static final String RATE_SMS_IP_KEY = "rl:sms:ip:";
    public static final String RATE_LOGIN_IP_KEY = "rl:login:ip:";
    public static final String RATE_SECKILL_VOUCHER_KEY = "rl:seckill:v:";
    public static final String RATE_SECKILL_USER_KEY = "rl:seckill:u:";
    public static final String RATE_SECKILL_PAIR_KEY = "rl:seckill:uv:";
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
