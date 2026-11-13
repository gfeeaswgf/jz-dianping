package com.hmdp.protection;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.UserController;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.exception.ProtectionUnavailableException;
import com.hmdp.ratelimit.*;
import com.hmdp.service.IUserService;
import com.hmdp.utils.ProtectionKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证真实 Spring AOP 代理的业务阻断、统一 HTTP 协议及可信 IP 解析。 */
class ProtectionRateLimitAspectTest {
    private ProtectionProperties properties;
    private RedisRateLimiter limiter;
    private IUserService userService;
    private MockMvc mvc;

    @BeforeEach void setup() {
        properties=new ProtectionProperties();
        limiter=mock(RedisRateLimiter.class);
        userService=mock(IUserService.class);
        UserController target=new UserController();
        ReflectionTestUtils.setField(target,"userService",userService);
        mvc=MockMvcBuilders.standaloneSetup(proxy(target,limiter,properties)).setControllerAdvice(new WebExceptionAdvice()).build();
    }

    static Object proxy(Object target, RedisRateLimiter limiter, ProtectionProperties p) {
        AspectJProxyFactory factory=new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new RateLimitAspect(limiter,new RateLimitPolicyResolver(p,new ProtectionKeys(p),new ClientIpResolver(p))));
        return factory.getProxy();
    }

    @Test void deniedNeverInvokesUserServiceAndRoundsUpRetry() throws Exception {
        when(limiter.acquire(anyList())).thenReturn(new RateLimitDecision(false,2001));
        mvc.perform(post("/user/code").param("phone","13800000000"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","3"))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.data.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.errorMsg").value("请求过于频繁，请稍后重试"));
        verifyNoInteractions(userService);
    }

    @Test void redisFailureIs503AndDoesNotProceed() throws Exception {
        when(limiter.acquire(anyList())).thenThrow(new ProtectionUnavailableException("RATE_REDIS_FAILURE"));
        mvc.perform(post("/user/code").param("phone","13800000000"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.code").value("SERVICE_UNAVAILABLE"));
        verifyNoInteractions(userService);
    }

    @Test void allowedCallsOriginalBusinessExactlyOnce() throws Exception {
        when(limiter.acquire(anyList())).thenReturn(new RateLimitDecision(true,0));
        when(userService.sendCode(anyString(),any())).thenReturn(Result.ok());
        mvc.perform(post("/user/code").param("phone","13800000000")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(userService,times(1)).sendCode(eq("13800000000"),any());
        verify(limiter,times(1)).acquire(anyList());
    }

    @Test void loginAndSendUseSeparateIpPolicies() throws Exception {
        RateLimitPolicyResolver r=new RateLimitPolicyResolver(properties,new ProtectionKeys(properties),new ClientIpResolver(properties));
        MockHttpServletRequest request=new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.8");
        RateRule send=r.resolve(RateLimitPolicy.SMS_SEND_IP,new Object[]{"13800000000"},request).get(0);
        RateRule login=r.resolve(RateLimitPolicy.LOGIN_IP,new Object[]{new LoginFormDTO()},request).get(0);
        assertEquals("rl:sms:ip:192.0.2.8",send.getKey()); assertEquals(20,send.getLimit());
        assertEquals("rl:login:ip:192.0.2.8",login.getKey()); assertEquals(60,login.getLimit());
        assertEquals(60000,login.getDurationMs());
        when(limiter.acquire(anyList())).thenReturn(new RateLimitDecision(false,1));
        mvc.perform(post("/user/login").contentType("application/json").content("{\"phone\":\"13800000000\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","1"));
        verifyNoInteractions(userService);
    }

    @Test void untrustedForwardedHeadersIgnored() {
        MockHttpServletRequest r=new MockHttpServletRequest(); r.setRemoteAddr("192.0.2.1");
        r.addHeader("X-Real-IP","203.0.113.9"); r.addHeader("X-Forwarded-For","203.0.113.9");
        assertEquals("192.0.2.1",new ClientIpResolver(properties).resolve(r));
    }

    @Test void trustedProxyAcceptsOnlySingleLiteralAndNeverHostname() {
        properties.setTrustedProxyAddresses(Collections.singletonList("127.0.0.1"));
        ClientIpResolver resolver=new ClientIpResolver(properties);
        MockHttpServletRequest r=new MockHttpServletRequest(); r.setRemoteAddr("127.0.0.1"); r.addHeader("X-Real-IP","203.0.113.9");
        assertEquals("203.0.113.9",resolver.resolve(r));
        for(String header:new String[]{"example.com","1.2.3.4, 5.6.7.8","999.2.3.4","[::1]","::1%eth0"}) {
            r.removeHeader("X-Real-IP"); r.addHeader("X-Real-IP",header);
            assertEquals("127.0.0.1",resolver.resolve(r));
        }
    }

    @Test void equivalentIpv6SharesBucket() {
        ClientIpResolver resolver=new ClientIpResolver(properties);
        MockHttpServletRequest a=new MockHttpServletRequest(),b=new MockHttpServletRequest();
        a.setRemoteAddr("2001:0db8:0:0:0:0:0:1"); b.setRemoteAddr("2001:db8::1");
        assertEquals(resolver.resolve(a),resolver.resolve(b));
    }
    @Test void trustedProxyRejectsDuplicateHeaderFields() {
        properties.setTrustedProxyAddresses(Collections.singletonList("127.0.0.1"));
        MockHttpServletRequest r=new MockHttpServletRequest();r.setRemoteAddr("127.0.0.1");
        r.addHeader("X-Real-IP","203.0.113.9");r.addHeader("X-Real-IP","203.0.113.10");
        assertEquals("127.0.0.1",new ClientIpResolver(properties).resolve(r));
    }
}

/** IP 规则的准确边界必须执行真实 Redis，不用 mock 计数器代替。 */
class ProtectionIpRateRedisTest extends com.hmdp.protection.support.RedisProtectionTestSupport {
    @Test void smsIpAllows20Rejects21() {
        String k=key("sms"); holdHash(k,0,19);
        assertEquals(0L,at("rate_limit",100,Collections.singletonList(k),"FIXED_WINDOW","20","60000").getResult().get(0));
        assertEquals(1L,at("rate_limit",100,Collections.singletonList(k),"FIXED_WINDOW","20","60000").getResult().get(0));
    }
    @Test void loginIpAllows60Rejects61() {
        String k=key("login"); holdHash(k,0,59);
        assertEquals(0L,at("rate_limit",100,Collections.singletonList(k),"FIXED_WINDOW","60","60000").getResult().get(0));
        assertEquals(1L,at("rate_limit",100,Collections.singletonList(k),"FIXED_WINDOW","60","60000").getResult().get(0));
    }
    @Test void ipWindowResetsAt60000() {
        String k=key("ip"); holdHash(k,0,60);
        assertEquals(1L,at("rate_limit",59999,Collections.singletonList(k),"FIXED_WINDOW","60","60000").getResult().get(0));
        assertEquals(0L,at("rate_limit",60000,Collections.singletonList(k),"FIXED_WINDOW","60","60000").getResult().get(0));
    }
}
