package com.hmdp.protection;

import com.hmdp.auth.*;
import com.hmdp.config.ProtectionProperties;
import com.hmdp.exception.*;
import com.hmdp.utils.ProtectionKeys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证短信端口的确定/不确定结果与安全补偿，不调用任何付费服务。 */
@ExtendWith(OutputCaptureExtension.class)
class ProtectionSmsSendTest {
    private static final String PHONE="13800000000";
    private StringRedisTemplate redis;
    private SmsSender sender;
    private SmsCodeService service;
    @BeforeEach void setup() {
        redis=mock(StringRedisTemplate.class);sender=mock(SmsSender.class);
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(Arrays.asList(0L,0L));
        when(sender.send(anyString(),anyString(),anyString())).thenReturn(SmsSendOutcome.ACCEPTED);
        service=new SmsCodeService(redis,new ProtectionProperties(),new ProtectionKeys(new ProtectionProperties()),sender);
    }
    @Test void acceptedSendsExactlyOnceWithSixDigitsAndDoesNotLogSecrets(CapturedOutput output) {
        service.sendCode(PHONE);
        org.mockito.ArgumentCaptor<String> code=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(PHONE),code.capture(),matches("[0-9a-f]{32}"));
        assertTrue(code.getValue().matches("[0-9]{6}"));
        assertFalse(output.getAll().contains(PHONE));assertFalse(output.getAll().contains(code.getValue()));
        verify(redis,times(1)).execute(any(RedisScript.class),anyList(),anyVararg());
    }
    @Test void explicitRejectionCleansOnlyOwnedCodeWithoutQuotaRefund() {
        when(sender.send(anyString(),anyString(),anyString())).thenReturn(SmsSendOutcome.REJECTED);
        when(redis.execute(any(RedisScript.class),eq(Arrays.asList("login:code:"+PHONE,"login:sms:issue:"+PHONE)),anyVararg())).thenReturn(1L);
        assertThrows(ProtectionUnavailableException.class,()->service.sendCode(PHONE));
        verify(redis).execute(any(RedisScript.class),eq(Arrays.asList("login:code:"+PHONE,"login:sms:issue:"+PHONE)),anyVararg());
        verify(redis,times(2)).execute(any(RedisScript.class),anyList(),anyVararg());
    }
    @Test void unknownResultRetainsReservationAndNeverRetries() {
        when(sender.send(anyString(),anyString(),anyString())).thenReturn(SmsSendOutcome.UNKNOWN);
        assertThrows(ProtectionUnavailableException.class,()->service.sendCode(PHONE));
        verify(sender,times(1)).send(anyString(),anyString(),anyString());
        verify(redis,times(1)).execute(any(RedisScript.class),anyList(),anyVararg());
    }
    @Test void unclassifiedProviderFailureDoesNotLeakOrDeleteCode(CapturedOutput output) {
        when(sender.send(anyString(),anyString(),anyString())).thenThrow(new RuntimeException("provider-raw "+PHONE));
        ProtectionUnavailableException e=assertThrows(ProtectionUnavailableException.class,()->service.sendCode(PHONE));
        assertNull(e.getCause()); assertFalse(output.getAll().contains(PHONE));
        verify(redis,times(1)).execute(any(RedisScript.class),anyList(),anyVararg());
    }
    @Test void reservationFailureDoesNotCallSender() {
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenThrow(new RuntimeException("raw credentials"));
        assertThrows(ProtectionUnavailableException.class,()->service.sendCode(PHONE));verifyNoInteractions(sender);
    }
    @Test void reservationDenialIs429AndDoesNotCallSender() {
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(Arrays.asList(1L,60000L));
        assertThrows(ProtectionRejectedException.class,()->service.sendCode(PHONE));verifyNoInteractions(sender);
    }
    @Test void malformedReservationOrCompensationFailsClosed() {
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(null);
        assertThrows(ProtectionUnavailableException.class,()->service.sendCode(PHONE));verifyNoInteractions(sender);
        when(redis.execute(any(RedisScript.class),anyList(),anyVararg())).thenReturn(Arrays.asList(0L,0L));
        when(sender.send(anyString(),anyString(),anyString())).thenReturn(SmsSendOutcome.REJECTED);
        when(redis.execute(any(RedisScript.class),eq(Arrays.asList("login:code:"+PHONE,"login:sms:issue:"+PHONE)),anyVararg()))
                .thenThrow(new RuntimeException("raw"));
        assertThrows(ProtectionUnavailableException.class,()->service.sendCode(PHONE));
    }
    @Test void invalidPhoneRejectedBeforeRedis() {
        assertThrows(ProtectionInvalidRequestException.class,()->service.sendCode("not-phone"));verifyNoInteractions(redis,sender);
    }
    @Test void mockSenderIsExplicitAndSilent(CapturedOutput output) {
        assertEquals(SmsSendOutcome.ACCEPTED,new MockSmsSender().send(PHONE,"654321","id"));
        assertFalse(output.getAll().contains("654321"));assertFalse(output.getAll().contains(PHONE));
    }
    @Test void sensitiveMapperLoggingIsSuppressedWithoutChangingYaml() {
        org.springframework.boot.logging.LoggingSystem system=org.springframework.boot.logging.LoggingSystem.get(getClass().getClassLoader());
        system.setLogLevel("com.hmdp.mapper.UserMapper",org.springframework.boot.logging.LogLevel.DEBUG);
        new ProtectionProperties().validate();
        assertEquals(org.springframework.boot.logging.LogLevel.INFO,system.getLoggerConfiguration("com.hmdp.mapper.UserMapper").getEffectiveLevel());
    }
}

/** 签发窗口和补偿所有权使用真实 Redis 测试，缺少端点时显式失败。 */
class ProtectionSmsSendRedisTest extends com.hmdp.protection.support.RedisProtectionTestSupport {
    private List<String> smsKeys() {return Arrays.asList(key("code"),key("issue"),key("cool"),key("hour"),key("day"),key("lock"));}
    private List<Long> reserve(long now,String issue) {return at("sms_reserve",now,smsKeys(),"654321",issue,"120000","60000","5","10").getResult();}
    @Test void cooldownRejects59999Allows60000AndResendKeepsFailureCount() {
        redis.opsForValue().set(key("fail"),"3",60,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0L,reserve(0,"one").get(0));
        assertEquals(1L,reserve(59999,"two").get(0));assertEquals(0L,reserve(60000,"two").get(0));
        assertEquals("3",redis.opsForValue().get(key("fail")));
    }
    @Test void hourAllows5Rejects6AndResetsAtNextHour() {
        holdHash(key("hour"),0,4);
        assertEquals(0L,reserve(100,"one").get(0));
        redis.delete(key("cool"));
        assertEquals(1L,reserve(60100,"two").get(0));assertEquals(0L,reserve(3600000,"two").get(0));
    }
    @Test void dayAllows10Rejects11AndResetsAtShanghaiMidnight() {
        long noon=12*3600000L;
        holdHash(key("day"),0,9);
        assertEquals(0L,reserve(noon,"one").get(0));redis.delete(key("cool"));
        assertEquals(1L,reserve(noon+60000,"two").get(0));
        assertEquals(0L,reserve(16*3600000L,"two").get(0));
    }
    @Test void lockedOrQuotaDeniedDoesNotOverwriteCodeOrPartiallyCharge() {
        redis.opsForValue().set(key("lock"),"1",60,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1L,reserve(100,"one").get(0)); assertFalse(redis.hasKey(key("hour")));assertFalse(redis.hasKey(key("code")));
        redis.delete(key("lock"));holdHash(key("day"),0,10);
        assertEquals(1L,reserve(100,"one").get(0));assertFalse(redis.hasKey(key("hour")));
    }
    @Test void staleFailureCannotDeleteNewIssueAndCorrectFailurePreservesQuotas() {
        reserve(100,"old");reserve(60100,"new");
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script=new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("lua/protection/sms_cleanup.lua"));script.setResultType(Long.class);
        assertEquals(0L,redis.execute(script,Arrays.asList(key("code"),key("issue")),"old"));
        assertEquals("new",redis.opsForValue().get(key("issue")));
        assertEquals(1L,redis.execute(script,Arrays.asList(key("code"),key("issue")),"new"));
        assertNull(redis.opsForValue().get(key("code")));assertEquals("2",redis.opsForHash().get(key("hour"),"count"));
        assertTrue(redis.getExpire(key("cool"),java.util.concurrent.TimeUnit.MILLISECONDS)>0);
    }
    @Test void damagedExistingStringsReturnStateErrorWithoutChangingQuotas() {
        for(String damaged:Arrays.asList("code","issue","lock")) {
            for(String k:smsKeys())redis.delete(k);
            redis.opsForValue().set(key("code"),"654321",60,java.util.concurrent.TimeUnit.SECONDS);
            redis.opsForValue().set(key("issue"),"valid-owner",60,java.util.concurrent.TimeUnit.SECONDS);
            redis.opsForValue().set(key(damaged),"code".equals(damaged)?"broken":"issue".equals(damaged)?"":"broken",60,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(3L,reserve(100,"new").get(0));
            assertFalse(redis.hasKey(key("hour")));assertFalse(redis.hasKey(key("day")));
            if(!"code".equals(damaged))assertEquals("654321",redis.opsForValue().get(key("code")));
        }
    }
}
