package com.hmdp.protection;

import com.hmdp.auth.TokenSessionService;
import com.hmdp.config.*;
import com.hmdp.controller.UserController;
import com.hmdp.dto.*;
import com.hmdp.exception.ProtectionUnavailableException;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证会话 Java 适配、ThreadLocal 清理和退出 MVC 路由；不伪造真实 Redis 证明。 */
class ProtectionTokenSessionTest {
    private static final String TOKEN="0123456789abcdef0123456789abcdef";
    private StringRedisTemplate redis;
    private TokenSessionService sessions;
    @BeforeEach void setup() {redis=mock(StringRedisTemplate.class);sessions=new TokenSessionService(redis,new ProtectionProperties(),new ProtectionKeys(new ProtectionProperties()));}
    @AfterEach void clearIdentity() {UserHolder.removeUser();}
    private UserDTO user() {UserDTO u=new UserDTO();u.setId(42L);u.setNickName("learner");u.setIcon("");return u;}

    @Test void createsCompatibleTokenWithAtomicTwentyFiveDayTtl() {
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(1L);
        String token=sessions.create(user());assertTrue(token.matches("[0-9a-f]{32}"));
        verify(redis).execute(any(RedisScript.class),eq(Collections.singletonList("login:token:"+token)),eq("2160000000"),
                eq("id"),eq("42"),eq("nickName"),eq("learner"),eq("icon"),eq(""));
    }
    @Test void readsAndRenewsUserWithoutExposingExtraFields() {
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(Arrays.asList("id","42","nickName","learner","icon",""));
        assertEquals(42L,sessions.resolveAndRefresh(TOKEN).getId());
        verify(redis).execute(any(RedisScript.class),eq(Collections.singletonList("login:token:"+TOKEN)),eq("2160000000"));
    }
    @Test void missingOrInvalidTokenNeverQueriesRedisAndLogoutIsIdempotent() {
        for(String token:Arrays.asList(null,"","bad-token","Bearer "+TOKEN)) {assertNull(sessions.resolveAndRefresh(token));sessions.logout(token);}
        verifyNoInteractions(redis);
    }
    @Test void missingHashDoesNotRecreateAndMalformedReplyFailsClosed() {
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(Collections.emptyList());
        assertNull(sessions.resolveAndRefresh(TOKEN));
        for(Object reply:Arrays.asList(Arrays.asList("id","not-number"),Arrays.asList("id","42","phone","secret"),Arrays.asList("__PROTECTION_ERROR__"))) {
            when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(reply);
            assertThrows(ProtectionUnavailableException.class,()->sessions.resolveAndRefresh(TOKEN));
        }
    }
    @Test void logoutDeletesOnlyCurrentKeyAndDoesNotReportRedisFailureAsSuccess() {
        UserHolder.saveUser(user());sessions.logout(TOKEN);verify(redis).delete("login:token:"+TOKEN);assertNull(UserHolder.getUser());
        doThrow(new RuntimeException("raw-token "+TOKEN)).when(redis).delete("login:token:"+TOKEN);
        assertThrows(ProtectionUnavailableException.class,()->sessions.logout(TOKEN));
    }
    @Test void refreshFailureLeavesNoUserHolder() {
        UserHolder.saveUser(user());TokenSessionService broken=mock(TokenSessionService.class);
        when(broken.resolveAndRefresh(any())).thenThrow(new ProtectionUnavailableException("TOKEN_FAILURE"));
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("authorization",TOKEN);
        assertThrows(ProtectionUnavailableException.class,()->new RefreshTokenInterceptor(broken).preHandle(request,new MockHttpServletResponse(),new Object()));
        assertNull(UserHolder.getUser());
    }
    @Test void requestCompletionAndAnonymousRequestClearStaleIdentity() throws Exception {
        RefreshTokenInterceptor interceptor=new RefreshTokenInterceptor(sessions);UserHolder.saveUser(user());
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),new Object()));assertNull(UserHolder.getUser());
        UserHolder.saveUser(user());interceptor.afterCompletion(new MockHttpServletRequest(),new MockHttpServletResponse(),new Object(),null);assertNull(UserHolder.getUser());
    }
    @Test void logoutRouteAllowsAnonymousAndDelegatesRawHeader() throws Exception {
        TokenSessionService port=mock(TokenSessionService.class);UserServiceImpl users=new UserServiceImpl();ReflectionTestUtils.setField(users,"tokenSessionService",port);
        UserController controller=new UserController();ReflectionTestUtils.setField(controller,"userService",users);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice()).build();
        mvc.perform(post("/user/logout").header("authorization",TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(port).logout(TOKEN);
        mvc.perform(post("/user/logout")).andExpect(status().isOk());verify(port).logout(null);
    }
}

/** 最小 MVC 上下文使用真实 Redis 会话验证退出后的 401，并隔离原数据库和消费者。 */
class ProtectionTokenSessionRedisTest extends com.hmdp.protection.support.RedisProtectionTestSupport {
    private TokenSessionService sessions() {
        ProtectionProperties p=new ProtectionProperties();p.setKeyPrefix(prefix);
        return new TokenSessionService(redis,p,new ProtectionKeys(p));
    }
    private UserDTO user(){UserDTO u=new UserDTO();u.setId(42L);u.setNickName("learner");return u;}
    @Test void tokenHashAndTtlAreCreatedTogetherAndLogoutMakesOldToken401() throws Exception {
        TokenSessionService sessions=sessions();String token=sessions.create(user());String tokenKey=key("login:token:"+token);
        assertTrue(redis.getExpire(tokenKey,java.util.concurrent.TimeUnit.MILLISECONDS)>2159990000L);
        UserServiceImpl users=new UserServiceImpl();ReflectionTestUtils.setField(users,"tokenSessionService",sessions);
        UserController controller=new UserController();ReflectionTestUtils.setField(controller,"userService",users);
        org.springframework.web.context.support.AnnotationConfigWebApplicationContext web=ProtectionContractRedisTest.context(controller,sessions);
        try {
            MockMvc mvc=MockMvcBuilders.webAppContextSetup(web).build();
            mvc.perform(get("/user/me").header("authorization",token)).andExpect(status().isOk());
            mvc.perform(post("/user/logout").header("authorization",token)).andExpect(status().isOk());
            mvc.perform(get("/user/me").header("authorization",token)).andExpect(status().isUnauthorized());
            mvc.perform(post("/user/logout").header("authorization",token)).andExpect(status().isOk());
            assertFalse(redis.hasKey(tokenKey));
        }finally {web.close();UserHolder.removeUser();}
    }
    @Test void refreshAndLogoutRaceCannotResurrectKey() throws Exception {
        TokenSessionService sessions=sessions();String token=sessions.create(user());String tokenKey=key("login:token:"+token);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> r=pool.submit(()->{start.await();sessions.resolveAndRefresh(token);return null;});
            java.util.concurrent.Future<?> d=pool.submit(()->{start.await();sessions.logout(token);return null;});
            start.countDown();r.get(5,java.util.concurrent.TimeUnit.SECONDS);d.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(redis.hasKey(tokenKey));assertNull(sessions.resolveAndRefresh(token));
        }finally{pool.shutdownNow();pool.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}
    }
    @Test void overflowingUserIdDoesNotRenewDamagedSession() {
        String token="0123456789abcdef0123456789abcdef",k=key("login:token:"+token);
        redis.opsForHash().put(k,"id","9223372036854775808");redis.expire(k,10,java.util.concurrent.TimeUnit.SECONDS);
        long before=redis.getExpire(k,java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThrows(ProtectionUnavailableException.class,()->sessions().resolveAndRefresh(token));
        assertTrue(redis.getExpire(k,java.util.concurrent.TimeUnit.MILLISECONDS)<=before);
    }
    /** 仅启用 MVC，不扫描主应用。 */
    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class MvcOnly { }
}
