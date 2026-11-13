package com.hmdp.ratelimit;

import com.hmdp.exception.ProtectionRejectedException;
import com.hmdp.exception.ProtectionInvalidRequestException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 在 Controller 业务方法之前准入，受保护方法不执行时不生成订单或操作数据库。 */
@Aspect
@Component
public class RateLimitAspect {
    private final RedisRateLimiter limiter;
    private final RateLimitPolicyResolver resolver;
    /**
     * @param limiter 原子准入组件
     * @param resolver 服务端规则解析器
     */
    public RateLimitAspect(RedisRateLimiter limiter,RateLimitPolicyResolver resolver) { this.limiter=limiter;this.resolver=resolver; }
    /**
     * 一次生成全部规则并执行一次准入；拒绝时不调用 proceed。
     * @param joinPoint 公共 Controller 方法
     * @param annotation 方法注解
     * @return 原方法返回值
     * @throws Throwable 原业务异常或安全保护异常
     */
    @Around("@annotation(annotation)")
    public Object guard(ProceedingJoinPoint joinPoint,RateLimit annotation) throws Throwable {
        Object attributes=RequestContextHolder.getRequestAttributes();
        if(!(attributes instanceof ServletRequestAttributes)) throw new ProtectionInvalidRequestException();
        RateLimitDecision decision=limiter.acquire(resolver.resolve(annotation.policy(),joinPoint.getArgs(),((ServletRequestAttributes)attributes).getRequest()));
        if(!decision.isAllowed()) throw new ProtectionRejectedException(decision.getRetryAfterMs());
        return joinPoint.proceed();
    }
}
