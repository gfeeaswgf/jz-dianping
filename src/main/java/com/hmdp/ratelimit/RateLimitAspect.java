package com.hmdp.ratelimit;

import com.hmdp.exception.ProtectionRejectedException;
import com.hmdp.exception.ProtectionInvalidRequestException;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class RateLimitAspect {

    private final RedisRateLimiter limiter;
    private final RateLimitPolicyResolver resolver;

    // 构造方法注入
    public RateLimitAspect(RedisRateLimiter limiter, RateLimitPolicyResolver resolver) {
        this.limiter = limiter;
        this.resolver = resolver;
    }

    /**
     * AOP环绕通知，实现接口限流
     */
    @Around("@annotation(annotation)")
    public Object guard(ProceedingJoinPoint joinPoint, RateLimit annotation) throws Throwable {

        // 1. 获取当前请求上下文
        Object attributes = RequestContextHolder.getRequestAttributes();

        // 2. 判断请求是否合法
        if (!(attributes instanceof ServletRequestAttributes)) {
            throw new ProtectionInvalidRequestException();
        }

        // 3. 获取当前HTTP请求
        ServletRequestAttributes requestAttributes = (ServletRequestAttributes) attributes;
        HttpServletRequest request = requestAttributes.getRequest();

        // 4. 获取限流策略
        RateLimitPolicy policy = annotation.policy();

        // 5. 获取被拦截方法的参数
        Object[] args = joinPoint.getArgs();

        // 6. 根据限流策略生成限流规则
        List<RateRule> rules = resolver.resolve(policy, args, request);

        // 7. 执行Redis限流判断
        RateLimitDecision decision = limiter.acquire(rules);

        // 8. 判断是否允许请求
        if (!decision.isAllowed()) {
            // 8.1 不允许请求，抛出限流异常
            throw new ProtectionRejectedException(decision.getRetryAfterMs());
        }

        // 9. 允许请求，执行原来的Controller方法
        return joinPoint.proceed();
    }
}