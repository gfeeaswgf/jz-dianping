package com.hmdp.protection;

import com.hmdp.auth.*;
import com.hmdp.config.ProtectionProperties;
import com.hmdp.dto.*;
import com.hmdp.exception.*;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.ProtectionKeys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 验证原子校验适配与登录数据库边界，错误文案不揭示用户注册状态。 */
@ExtendWith(OutputCaptureExtension.class)
class ProtectionSmsVerifyTest {
    private StringRedisTemplate redis;
    private SmsCodeService sms;
    @BeforeEach void setup() {
        redis=mock(StringRedisTemplate.class);
        sms=new SmsCodeService(redis,new ProtectionProperties(),new ProtectionKeys(new ProtectionProperties()),mock(SmsSender.class));
    }
    @Test void successConsumesUsingOneScriptAndInvalidResultIsUniform() {
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(Arrays.asList(0L,0L),Arrays.asList(2L,0L));
        assertTrue(sms.verifyAndConsume("13800000000","654321"));
        assertFalse(sms.verifyAndConsume("13800000000","654321"));
        verify(redis,times(2)).execute(any(RedisScript.class),eq(Arrays.asList("login:code:13800000000","login:sms:issue:13800000000","login:verify:fail:13800000000","login:verify:lock:13800000000")),
                eq("654321"),eq("5"),eq("900000"),eq("900000"));
    }
    @Test void lockedOrRedisUnavailableFailsClosed() {
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(Arrays.asList(1L,900000L));
        assertThrows(ProtectionRejectedException.class,()->sms.verifyAndConsume("13800000000","654321"));
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(Arrays.asList(3L,0L));
        assertThrows(ProtectionUnavailableException.class,()->sms.verifyAndConsume("13800000000","654321"));
    }
    @Test void malformedCodeIs400WithoutComparison() {
        for(String code:new String[]{"12345","abcdef","1234567",""," 123456"})
            assertThrows(ProtectionInvalidRequestException.class,()->sms.verifyAndConsume("13800000000",code));
        verifyNoInteractions(redis);
    }
    @Test void falseVerificationNeverQueriesRegistrationState() {
        SmsCodeService gateway=mock(SmsCodeService.class);UserMapper mapper=mock(UserMapper.class);
        UserServiceImpl service=userService(gateway,mapper); LoginFormDTO form=form();
        for(int i=0;i<2;i++) {
            Result result=service.login(form,null);
            assertFalse(result.getSuccess());assertEquals("验证码错误或已失效",result.getErrorMsg());
        }
        verifyNoInteractions(mapper);
    }
    @Test void lockedOrFaultedVerificationNeverQueriesDatabase() {
        SmsCodeService gateway=mock(SmsCodeService.class);UserMapper mapper=mock(UserMapper.class);
        UserServiceImpl service=userService(gateway,mapper);
        when(gateway.verifyAndConsume(anyString(),anyString())).thenThrow(new ProtectionRejectedException(900000));
        assertThrows(ProtectionRejectedException.class,()->service.login(form(),null));verifyNoInteractions(mapper);
        doThrow(new ProtectionUnavailableException("VERIFY_FAILURE")).when(gateway).verifyAndConsume(anyString(),anyString());
        assertThrows(ProtectionUnavailableException.class,()->service.login(form(),null));verifyNoInteractions(mapper);
    }
    @Test void databaseExceptionDoesNotLeakPhoneOrCause(CapturedOutput output) {
        SmsCodeService gateway=mock(SmsCodeService.class);UserMapper mapper=mock(UserMapper.class);
        when(gateway.verifyAndConsume(anyString(),anyString())).thenReturn(true);
        when(mapper.selectOne(any())).thenThrow(new RuntimeException("SQL parameters 13800000000 654321"));
        ProtectionUnavailableException failure=assertThrows(ProtectionUnavailableException.class,()->userService(gateway,mapper).login(form(),null));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("13800000000"));
        assertFalse(output.getAll().contains("13800000000"));assertFalse(output.getAll().contains("654321"));
    }
    private UserServiceImpl userService(SmsCodeService gateway,UserMapper mapper) {
        UserServiceImpl service=new UserServiceImpl();ReflectionTestUtils.setField(service,"smsCodeService",gateway);ReflectionTestUtils.setField(service,"baseMapper",mapper);return service;
    }
    private LoginFormDTO form() {LoginFormDTO f=new LoginFormDTO();f.setPhone("13800000000");f.setCode("654321");return f;}
}

/** 真实 Redis 保证一次消费和错误计数原子性；环境缺失会明确失败。 */
class ProtectionSmsVerifyRedisTest extends com.hmdp.protection.support.RedisProtectionTestSupport {
    private List<String> verificationKeys() {return Arrays.asList(key("code"),key("issue"),key("fail"),key("lock"));}
    private void issue() {
        redis.opsForValue().set(key("code"),"654321",120,java.util.concurrent.TimeUnit.SECONDS);
        redis.opsForValue().set(key("issue"),"issue",120,java.util.concurrent.TimeUnit.SECONDS);
    }
    private List<Long> verifyCode(String code) {return raw("sms_verify",verificationKeys(),code,"5","900000","900000");}
    @Test void fourthDoesNotLockFifthLocksAndCorrectCodeIsStillRejected() {
        issue();for(int i=0;i<4;i++)assertEquals(2L,verifyCode("111111").get(0));
        assertFalse(redis.hasKey(key("lock")));assertEquals(1L,verifyCode("111111").get(0));
        long before=redis.getExpire(key("lock"),java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1L,verifyCode("654321").get(0));
        assertTrue(redis.getExpire(key("lock"),java.util.concurrent.TimeUnit.MILLISECONDS)<=before);
    }
    @Test void lockActuallyExpiresAndMissingCodeDoesNotCreateFailure() throws Exception {
        issue();redis.opsForValue().set(key("lock"),"1",60,java.util.concurrent.TimeUnit.SECONDS);
        expireAndAwait(key("lock"));assertEquals(0L,verifyCode("654321").get(0));
        assertEquals(2L,verifyCode("654321").get(0));assertFalse(redis.hasKey(key("fail")));
    }
    @Test void successfulConsumptionDeletesAllAndFailureDoesNotRenewTtl() {
        issue();verifyCode("111111");long before=redis.getExpire(key("fail"),java.util.concurrent.TimeUnit.MILLISECONDS);
        verifyCode("111111");assertTrue(redis.getExpire(key("fail"),java.util.concurrent.TimeUnit.MILLISECONDS)<=before);
        assertEquals(0L,verifyCode("654321").get(0));
        for(String k:verificationKeys())assertFalse(redis.hasKey(k));
    }
    @Test void sameCodeConcurrentConsumptionSucceedsExactlyOnce() throws Exception {
        issue();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Long>> results=new ArrayList<>();
        try {
            List<String> keys=verificationKeys();
            for(int i=0;i<50;i++)results.add(pool.submit(()->{start.await();return raw("sms_verify",keys,"654321","5","900000","900000").get(0);}));
            start.countDown();long successes=0,failures=0;
            for(java.util.concurrent.Future<Long> future:results){long value=future.get(10,java.util.concurrent.TimeUnit.SECONDS);if(value==0)successes++;else if(value==2)failures++;}
            assertEquals(1,successes);assertEquals(49,failures);
        }finally {pool.shutdownNow();assertTrue(pool.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void mismatchedStateOrCorruptCounterIs503WithoutDeletingCode() {
        redis.opsForValue().set(key("code"),"654321",60,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(3L,verifyCode("654321").get(0));
        issue();redis.opsForValue().set(key("fail"),"wrong",60,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(3L,verifyCode("654321").get(0));assertEquals("654321",redis.opsForValue().get(key("code")));
    }
    @Test void damagedIssueOrLockReturnsStateErrorBeforeComparingOrCounting() {
        issue();redis.opsForValue().set(key("issue"),"",60,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(3L,verifyCode("654321").get(0));assertFalse(redis.hasKey(key("fail")));
        issue();redis.opsForValue().set(key("lock"),"broken",60,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(3L,verifyCode("111111").get(0));assertFalse(redis.hasKey(key("fail")));
    }
    @Test void resendRacingOldCodeCannotDeleteNewIssue() throws Exception {
        issue();
        List<String> verifyKeys=verificationKeys();
        List<String> sendKeys=Arrays.asList(key("code"),key("issue"),key("cool"),key("hour"),key("day"),key("lock"));
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<Long> old=pool.submit(()->{start.await();return raw("sms_verify",verifyKeys,"654321","5","900000","900000").get(0);});
            java.util.concurrent.Future<Long> resend=pool.submit(()->{start.await();return raw("sms_reserve",sendKeys,"111111","new-owner","120000","60000","5","10").get(0);});
            start.countDown();long oldResult=old.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(oldResult==0 || oldResult==2);assertEquals(0L,resend.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("111111",redis.opsForValue().get(key("code")));assertEquals("new-owner",redis.opsForValue().get(key("issue")));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
