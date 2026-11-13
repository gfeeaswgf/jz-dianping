package com.hmdp.ratelimit;

import java.lang.annotation.*;

/** 在 Controller 公共方法执行前应用服务端固定策略，不接收客户端表达式。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    /**
     * @return 服务端配置的限流策略
     */
    RateLimitPolicy policy();
}
