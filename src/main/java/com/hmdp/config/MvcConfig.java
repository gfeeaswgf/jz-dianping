package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.auth.TokenSessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
/** 保持原双拦截器顺序；退出允许匿名调用，使过期或重复退出也能幂等成功。 */
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private TokenSessionService tokenSessionService;

    @Override
    /**
     * @param registry MVC 拦截器注册表，身份恢复先于登录检查
     */
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login",
                        "/user/logout"
                ).order(1);
        // token刷新的拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(tokenSessionService)).addPathPatterns("/**").order(0);
    }
}
