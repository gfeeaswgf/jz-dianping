package com.hmdp.protection;

import com.hmdp.auth.*;
import com.hmdp.config.*;
import com.hmdp.controller.*;
import com.hmdp.dto.*;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.ratelimit.*;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 跨组件的错误协议与日志回归；数据库及短信端口使用替身，保留真实业务协调代码。 */
@ExtendWith(OutputCaptureExtension.class)
class ProtectionContractTest {
    @Test void explicitYamlTestConfigurationUsesSeparateDatabaseWithoutEnvironmentVariables(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path config=dir.resolve("redis.yaml");
        java.nio.file.Files.write(config,Arrays.asList("spring:","  redis:","    host: 192.0.2.10","    port: 6380","    password: test-secret","    database: 0"),java.nio.charset.StandardCharsets.UTF_8);
        String oldPath=System.getProperty("hmdp.test.redis.config"),oldDb=System.getProperty("hmdp.test.redis.database");
        try {
            System.setProperty("hmdp.test.redis.config",config.toString());System.setProperty("hmdp.test.redis.database","15");
            org.springframework.data.redis.connection.RedisStandaloneConfiguration c=com.hmdp.protection.support.RedisProtectionTestSupport.isolatedConfiguration();
            assertEquals("192.0.2.10",c.getHostName());assertEquals(6380,c.getPort());assertEquals(15,c.getDatabase());
            assertEquals("test-secret",new String(c.getPassword().get()));
            System.setProperty("hmdp.test.redis.database","0");
            assertThrows(IllegalStateException.class,com.hmdp.protection.support.RedisProtectionTestSupport::isolatedConfiguration);
        } finally {
            if(oldPath==null)System.clearProperty("hmdp.test.redis.config");else System.setProperty("hmdp.test.redis.config",oldPath);
            if(oldDb==null)System.clearProperty("hmdp.test.redis.database");else System.setProperty("hmdp.test.redis.database",oldDb);
        }
    }
    @BeforeEach void frameworkLogLevelMatchesApplication() {
        org.springframework.boot.logging.LoggingSystem logs=org.springframework.boot.logging.LoggingSystem.get(getClass().getClassLoader());
        logs.setLogLevel("org.springframework",org.springframework.boot.logging.LogLevel.INFO);
        logs.setLogLevel("io.lettuce",org.springframework.boot.logging.LogLevel.INFO);
        logs.setLogLevel("com.hmdp",org.springframework.boot.logging.LogLevel.DEBUG);
    }
    @AfterEach void cleanup(){UserHolder.removeUser();}

    @Test void malformedLoginBodyReturns400WithoutLoggingSensitiveInput(CapturedOutput output) throws Exception {
        UserController controller=new UserController();
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice()).build();
        mvc.perform(post("/user/login").contentType("application/json")
                .content("{\"phone\":\"13800000000\",\"code\":secret654321}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorMsg").value("请求参数不合法"));
        assertFalse(output.getAll().contains("13800000000"));assertFalse(output.getAll().contains("secret654321"));
    }
    @Test void nonNumericVoucherReturns400WithoutEnteringBusiness() throws Exception {
        VoucherOrderController controller=new VoucherOrderController();
        mvc(controller).perform(post("/voucher-order/seckill/not-a-number")).andExpect(status().isBadRequest());
    }
    @Test void ambiguousReservationTimeoutDoesNotRetryOrInvokeSms(CapturedOutput output) throws Exception {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);SmsSender sender=mock(SmsSender.class);AtomicInteger count=new AtomicInteger();
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenAnswer(call->{count.incrementAndGet();throw new RuntimeException("raw 13800000000 secret654321");});
        SmsCodeService sms=new SmsCodeService(redis,new ProtectionProperties(),new ProtectionKeys(new ProtectionProperties()),sender);
        UserServiceImpl service=new UserServiceImpl();ReflectionTestUtils.setField(service,"smsCodeService",sms);
        UserController controller=new UserController();ReflectionTestUtils.setField(controller,"userService",service);
        mvc(controller).perform(post("/user/code").param("phone","13800000000"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.code").value("SERVICE_UNAVAILABLE"));
        assertEquals(1,count.get());verifyNoInteractions(sender);
        assertFalse(output.getAll().contains("secret654321"));assertFalse(output.getAll().contains("13800000000"));
    }
    @Test void unregisteredExpiredAndConsumedCodesUseSameFailureBody() throws Exception {
        SmsCodeService sms=mock(SmsCodeService.class);UserMapper mapper=mock(UserMapper.class);
        UserServiceImpl service=new UserServiceImpl();ReflectionTestUtils.setField(service,"smsCodeService",sms);ReflectionTestUtils.setField(service,"baseMapper",mapper);
        UserController controller=new UserController();ReflectionTestUtils.setField(controller,"userService",service);
        MockMvc mvc=mvc(controller);
        String a=mvc.perform(post("/user/login").contentType("application/json").content("{\"phone\":\"13800000000\",\"code\":\"654321\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorMsg").value("验证码错误或已失效")).andReturn().getResponse().getContentAsString();
        String b=mvc.perform(post("/user/login").contentType("application/json").content("{\"phone\":\"13900000000\",\"code\":\"111111\"}"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(a,b);verifyNoInteractions(mapper);
    }
    @Test void logoutRedisFailureIs503AndDoesNotLogToken(CapturedOutput output) throws Exception {
        String token="0123456789abcdef0123456789abcdef";
        StringRedisTemplate redis=mock(StringRedisTemplate.class);when(redis.delete(anyString())).thenThrow(new RuntimeException(token));
        TokenSessionService sessions=new TokenSessionService(redis,new ProtectionProperties(),new ProtectionKeys(new ProtectionProperties()));
        UserServiceImpl users=new UserServiceImpl();ReflectionTestUtils.setField(users,"tokenSessionService",sessions);
        UserController controller=new UserController();ReflectionTestUtils.setField(controller,"userService",users);
        mvc(controller).perform(post("/user/logout").header("authorization",token)).andExpect(status().isServiceUnavailable());
        assertFalse(output.getAll().contains(token));
    }
    private MockMvc mvc(Object controller){return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice()).build();}
}

/** 端到端 Lua/Token 证据仅在明确隔离 Redis 中采集，缺配置不跳过。 */
class ProtectionContractRedisTest extends com.hmdp.protection.support.RedisProtectionTestSupport {
    @Test void realReservationExecutedButReplyTimedOutNeverResends() {
        ProtectionProperties p=properties();SmsSender sender=mock(SmsSender.class);StringRedisTemplate timed=spy(redis);
        AtomicInteger executions=new AtomicInteger();
        doAnswer(call->{executions.incrementAndGet();call.callRealMethod();throw new RuntimeException("simulated reply loss");})
                .when(timed).execute(any(RedisScript.class),anyList(),anyVararg());
        registerSmsKeys("13800000000");
        SmsCodeService sms=new SmsCodeService(timed,p,new ProtectionKeys(p),sender);
        assertThrows(com.hmdp.exception.ProtectionUnavailableException.class,()->sms.sendCode("13800000000"));
        assertEquals(1,executions.get());verifyNoInteractions(sender);
        assertTrue(redis.getExpire(key("login:sms:cooldown:13800000000"),java.util.concurrent.TimeUnit.MILLISECONDS)>0);
    }
    @Test void realSendConsumeTokenLogoutAndReplayContract() throws Exception {
        ProtectionProperties p=properties();java.util.concurrent.atomic.AtomicReference<String> sent=new java.util.concurrent.atomic.AtomicReference<>();
        SmsSender sender=(phone,code,issue)->{sent.set(code);return SmsSendOutcome.ACCEPTED;};
        SmsCodeService sms=new SmsCodeService(redis,p,new ProtectionKeys(p),sender);
        TokenSessionService sessions=new TokenSessionService(redis,p,new ProtectionKeys(p));
        UserMapper mapper=mock(UserMapper.class);User user=new User();user.setId(42L);user.setNickName("learner");
        when(mapper.selectOne(any())).thenReturn(user);
        UserServiceImpl users=new UserServiceImpl();ReflectionTestUtils.setField(users,"smsCodeService",sms);ReflectionTestUtils.setField(users,"tokenSessionService",sessions);ReflectionTestUtils.setField(users,"baseMapper",mapper);
        UserController controller=new UserController();ReflectionTestUtils.setField(controller,"userService",users);
        Object proxy=ProtectionRateLimitAspectTest.proxy(controller,new RedisRateLimiter(redis),p);
        org.springframework.web.context.support.AnnotationConfigWebApplicationContext web=context(proxy,sessions);
        registerSmsKeys("13800000000");key("rl:sms:ip:127.0.0.1");key("rl:login:ip:127.0.0.1");
        try {
            MockMvc mvc=MockMvcBuilders.webAppContextSetup(web).build();
            mvc.perform(post("/user/code").param("phone","13800000000")).andExpect(status().isOk());
            mvc.perform(post("/user/code").param("phone","13800000000")).andExpect(status().isTooManyRequests());
            String body="{\"phone\":\"13800000000\",\"code\":\""+sent.get()+"\"}";
            String reply=mvc.perform(post("/user/login").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString();
            String token=new com.fasterxml.jackson.databind.ObjectMapper().readTree(reply).get("data").asText();key("login:token:"+token);
            mvc.perform(post("/user/login").contentType("application/json").content(body)).andExpect(jsonPath("$.success").value(false));
            mvc.perform(get("/user/me").header("authorization",token)).andExpect(status().isOk());
            mvc.perform(post("/user/logout").header("authorization",token)).andExpect(status().isOk());
            mvc.perform(get("/user/me").header("authorization",token)).andExpect(status().isUnauthorized());
        }finally{web.close();UserHolder.removeUser();}
    }
    @Test void connectionFailureUsesSelectedLoopbackPortWithoutTouchingBusinessRedis() throws Exception {
        int port;
        try(java.net.ServerSocket socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",port),
                org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().commandTimeout(java.time.Duration.ofMillis(500)).shutdownTimeout(java.time.Duration.ZERO).build());
        factory.afterPropertiesSet();
        try{RedisRateLimiter limiter=new RedisRateLimiter(new StringRedisTemplate(factory));
            assertThrows(com.hmdp.exception.ProtectionUnavailableException.class,()->limiter.acquire(Collections.singletonList(RateRule.cooldown(key("fault"),2000))));
        }finally{factory.destroy();}
    }
    private ProtectionProperties properties(){ProtectionProperties p=new ProtectionProperties();p.setKeyPrefix(prefix);return p;}
    private void registerSmsKeys(String phone){for(String base:Arrays.asList("login:code:","login:sms:issue:","login:sms:cooldown:","login:sms:hour:","login:sms:day:","login:verify:fail:","login:verify:lock:"))key(base+phone);}
    static org.springframework.web.context.support.AnnotationConfigWebApplicationContext context(Object controller,TokenSessionService sessions){
        org.springframework.web.context.support.AnnotationConfigWebApplicationContext web=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        web.setServletContext(new org.springframework.mock.web.MockServletContext());
        web.addBeanFactoryPostProcessor(factory->{factory.registerSingleton("userController",controller);factory.registerSingleton("tokenSessionService",sessions);factory.registerSingleton("advice",new WebExceptionAdvice());});
        web.register(MvcConfig.class,ProtectionTokenSessionRedisTest.MvcOnly.class);web.refresh();return web;
    }
}
