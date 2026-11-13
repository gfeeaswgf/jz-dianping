package com.hmdp.utils;

import com.hmdp.auth.TokenSessionService;
import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
/** 恢复身份并原子续期；异常与请求结束均清理 ThreadLocal，权限检查仍由登录拦截器负责。 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final TokenSessionService tokenSessionService;

    /**

     * @param tokenSessionService 会话存储与续期组件

     */
    public RefreshTokenInterceptor(TokenSessionService tokenSessionService) {
        this.tokenSessionService = tokenSessionService;
    }

    /**

     * @param request 当前请求

     * @param response 当前响应

     * @param handler 后续处理器

     * @return 放行至登录检查

     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserHolder.removeUser();
        try {
            UserDTO user = tokenSessionService.resolveAndRefresh(request.getHeader("authorization"));
            if (user != null) UserHolder.saveUser(user);
            return true;
        } catch (RuntimeException e) { UserHolder.removeUser(); throw e; }
    }

    /**

     * @param request 当前请求

     * @param response 当前响应

     * @param handler 处理器

     * @param ex 处理异常，可能为空

     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
