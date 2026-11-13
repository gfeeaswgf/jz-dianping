package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.exception.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
/** 统一返回协议；保护异常仅记录固定操作码，避免异常链泄露凭证。 */
public class WebExceptionAdvice {

    /**

     * @param e 额度或冷却拒绝

     * @return HTTP 429 和向上取整的等待秒数

     */
    @ExceptionHandler(ProtectionRejectedException.class)
    public ResponseEntity<Result> handleRejected(ProtectionRejectedException e) {
        long seconds=(e.getRetryAfterMs()-1)/1000+1;
        Map<String,Object> data=new HashMap<>(); data.put("code","RATE_LIMITED");data.put("retryAfterSeconds",seconds);
        return ResponseEntity.status(429).header("Retry-After",String.valueOf(seconds))
                .body(new Result(false,"请求过于频繁，请稍后重试",data,null));
    }

    /**

     * @param e 已脱敏的系统故障

     * @return HTTP 503，不将系统异常伪装成限流

     */
    @ExceptionHandler(ProtectionUnavailableException.class)
    public ResponseEntity<Result> handleUnavailable(ProtectionUnavailableException e) {
        log.warn("protection_unavailable operation={}",e.getOperationCode());
        Map<String,Object> data=new HashMap<>();data.put("code","SERVICE_UNAVAILABLE");
        return ResponseEntity.status(503).body(new Result(false,"请求暂不可用，请稍后重试",data,null));
    }

    /**

     * @param e 不含原文的参数异常

     * @return HTTP 400

     */
    @ExceptionHandler(ProtectionInvalidRequestException.class)
    public ResponseEntity<Result> handleInvalid(ProtectionInvalidRequestException e) {
        return ResponseEntity.badRequest().body(Result.fail("请求参数不合法"));
    }

    /**
     * 保护接口在进入 AOP 前也可能绑定失败，不能把含原始正文的解析异常交给通用日志。
     * @param e 请求体或路径参数解析异常，不记录原文
     * @param request 用已匹配路由判断范围，避免编码路径绕过或改变其他业务行为
     * @return 本模块统一 400；其他接口沿用原通用处理
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result> handleBinding(RuntimeException e, HttpServletRequest request) {
        Object pattern=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if ("/user/login".equals(pattern) || "/user/code".equals(pattern) || "/user/logout".equals(pattern)
                || "/voucher-order/seckill/{id}".equals(pattern)) {
            return ResponseEntity.badRequest().body(Result.fail("请求参数不合法"));
        }
        return ResponseEntity.ok(handleRuntimeException(e));
    }

    @ExceptionHandler(RuntimeException.class)
    /**
     * @param e 其他业务运行时异常
     * @return 原有通用失败结果
     */
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
