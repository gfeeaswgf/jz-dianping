package com.hmdp.protection;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.exception.ProtectionInvalidRequestException;
import com.hmdp.exception.ProtectionUnavailableException;
import com.hmdp.ratelimit.RateLimitDecision;
import com.hmdp.ratelimit.RateRule;
import com.hmdp.ratelimit.RedisRateLimiter;
import com.hmdp.utils.ProtectionKeys;
import org.junit.jupiter.api.Test;
import com.hmdp.protection.support.RedisProtectionTestSupport;
import com.hmdp.protection.support.LuaTimeHarness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证复合限流适配器的输入约束和故障关闭行为，真实 Lua 边界在隔离 Redis 用例中验证。 */
class ProtectionRateLimiterTest {
    @Test void unitRejectsEmptyAndDuplicateRulesBeforeRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisRateLimiter limiter = new RedisRateLimiter(redis);
        assertThrows(ProtectionInvalidRequestException.class, () -> limiter.acquire(Collections.emptyList()));
        assertThrows(ProtectionInvalidRequestException.class, () -> limiter.acquire(Arrays.asList(
                RateRule.fixedWindow("a", 100, 1000), RateRule.cooldown("a", 2000))));
        verifyNoInteractions(redis);
    }

    @Test void unitRejectsInvalidRuleAndMoreThanEightDimensions() {
        assertThrows(ProtectionInvalidRequestException.class, () -> RateRule.fixedWindow("a", 0, 1000));
        assertThrows(ProtectionInvalidRequestException.class, () -> RateRule.cooldown("a", -1));
        RedisRateLimiter limiter = new RedisRateLimiter(mock(StringRedisTemplate.class));
        java.util.List<RateRule> rules = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) rules.add(RateRule.fixedWindow("k" + i, 1, 1000));
        assertThrows(ProtectionInvalidRequestException.class, () -> limiter.acquire(rules));
    }

    @Test void unitExecutesAllDimensionsInOneScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyVararg()))
                .thenReturn(Arrays.asList(0L, 0L));
        RateLimitDecision result = new RedisRateLimiter(redis).acquire(Arrays.asList(
                RateRule.fixedWindow("v", 100, 1000), RateRule.fixedWindow("u", 5, 10000),
                RateRule.cooldown("uv", 2000)));
        assertTrue(result.isAllowed());
        verify(redis, times(1)).execute(any(RedisScript.class), eq(Arrays.asList("v", "u", "uv")),
                eq("FIXED_WINDOW"), eq("100"), eq("1000"), eq("FIXED_WINDOW"), eq("5"), eq("10000"),
                eq("COOLDOWN"), eq("1"), eq("2000"));
    }

    @Test void unitMapsDenialWithoutRetrying() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyVararg()))
                .thenReturn(Arrays.asList(1L, 1999L));
        RateLimitDecision d = new RedisRateLimiter(redis).acquire(Collections.singletonList(RateRule.cooldown("a", 2000)));
        assertFalse(d.isAllowed());
        assertEquals(1999, d.getRetryAfterMs());
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), anyVararg());
    }

    @Test void unitFailsClosedOnNullMalformedAndStateError() {
        for (java.util.List<?> reply : Arrays.asList(null, Collections.emptyList(), Arrays.asList(3L, 0L),
                Arrays.asList(1L, -1L), Arrays.asList(0L, 5L), Arrays.asList("secret", 0L))) {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.execute(any(RedisScript.class), anyList(), anyVararg())).thenReturn(reply);
            assertThrows(ProtectionUnavailableException.class, () -> new RedisRateLimiter(redis)
                    .acquire(Collections.singletonList(RateRule.cooldown("a", 2000))));
        }
    }

    @Test void unitSanitizesTransportFailureAndNeverRetries() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyVararg()))
                .thenThrow(new IllegalStateException("sensitive-value"));
        ProtectionUnavailableException e = assertThrows(ProtectionUnavailableException.class,
                () -> new RedisRateLimiter(redis).acquire(Collections.singletonList(RateRule.cooldown("a", 2000))));
        assertFalse(e.toString().contains("sensitive-value"));
        assertNull(e.getCause());
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), anyVararg());
    }

    @Test void unitValidatesConfigurationAndControlledKeyNamespace() {
        ProtectionProperties p = new ProtectionProperties();
        p.setKeyPrefix("test:protection:run:");
        p.validate();
        assertEquals("test:protection:run:login:code:13800000000", new ProtectionKeys(p).key("login:code:", "13800000000"));
        p.setSeckillUserVoucherCooldownMs(0);
        assertThrows(IllegalArgumentException.class, p::validate);
        ProtectionProperties bad = new ProtectionProperties();
        bad.setSmsMode("real-unknown");
        assertThrows(IllegalArgumentException.class, bad::validate);
        assertThrows(ProtectionInvalidRequestException.class, () -> new ProtectionKeys(new ProtectionProperties()).key("arbitrary", "x"));
    }

}

/** 环境缺失时明确报错；不会用模拟器替代真实 Redis 原子性验证。 */
class ProtectionRateLimiterRedisTest extends RedisProtectionTestSupport {
        @Test void voucherAllows100Rejects101() {
            String v=key("v"); holdHash(v,0,99);
            assertEquals(0L,at("rate_limit",100,Collections.singletonList(v),"FIXED_WINDOW","100","1000").getResult().get(0));
            assertEquals(1L,at("rate_limit",100,Collections.singletonList(v),"FIXED_WINDOW","100","1000").getResult().get(0));
        }
        @Test void voucherResetsAt1000Not999() {
            String v=key("v"); holdHash(v,0,100);
            assertEquals(1L,at("rate_limit",999,Collections.singletonList(v),"FIXED_WINDOW","100","1000").getResult().get(0));
            assertEquals(0L,at("rate_limit",1000,Collections.singletonList(v),"FIXED_WINDOW","100","1000").getResult().get(0));
        }
        @Test void userAllows5Rejects6AndResetsAt10000Not9999() {
            String u=key("u"); holdHash(u,0,4);
            assertEquals(0L,at("rate_limit",100,Collections.singletonList(u),"FIXED_WINDOW","5","10000").getResult().get(0));
            assertEquals(1L,at("rate_limit",9999,Collections.singletonList(u),"FIXED_WINDOW","5","10000").getResult().get(0));
            assertEquals(0L,at("rate_limit",10000,Collections.singletonList(u),"FIXED_WINDOW","5","10000").getResult().get(0));
        }
        @Test void pairRejects1999Allows2000AcrossFixedBoundary() {
            String pair=key("pair"); redis.opsForValue().set(pair,"2900",60,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1L,at("rate_limit",2000,Collections.singletonList(pair),"COOLDOWN","1","2000").getResult().get(0));
            assertEquals(1L,at("rate_limit",2899,Collections.singletonList(pair),"COOLDOWN","1","2000").getResult().get(0));
            assertEquals(0L,at("rate_limit",2900,Collections.singletonList(pair),"COOLDOWN","1","2000").getResult().get(0));
        }
        @Test void rejectionNeverWritesOtherDimensions() {
            for(int blocked=0;blocked<3;blocked++) {
                String v=key("v"+blocked),u=key("u"+blocked),p=key("p"+blocked);
                if(blocked==0) holdHash(v,0,100);
                if(blocked==1) holdHash(u,0,5);
                if(blocked==2) redis.opsForValue().set(p,"2100",60,java.util.concurrent.TimeUnit.SECONDS);
                LuaTimeHarness.Probe probe=at("rate_limit",100,Arrays.asList(v,u,p),"FIXED_WINDOW","100","1000","FIXED_WINDOW","5","10000","COOLDOWN","1","2000");
                assertEquals(1L,probe.getResult().get(0));
                if(blocked!=0) assertNull(probe.getValue(v));
                if(blocked!=1) assertNull(probe.getValue(u));
                if(blocked!=2) assertNull(probe.getValue(p));
            }
        }
        @Test void corruptAndMissingTtlFailBeforeAnyWrite() {
            String bad=key("bad"), untouched=key("clean");
            redis.opsForValue().set(bad,"wrong-type");
            assertEquals(3L,at("rate_limit",100,Arrays.asList(untouched,bad),"FIXED_WINDOW","100","1000","FIXED_WINDOW","5","10000").getResult().get(0));
            assertFalse(redis.hasKey(untouched));
            holdHash(bad=key("hash"),0,-1);
            assertEquals(3L,at("rate_limit",100,Collections.singletonList(bad),"FIXED_WINDOW","5","10000").getResult().get(0));
        }
        @Test void twoClientsShareQuotaAndRealCooldownExpires() throws Exception {
            String p=key("realPair");
            RedisRateLimiter first=new RedisRateLimiter(redis),second=new RedisRateLimiter(newClient());
            long before=System.nanoTime();
            assertTrue(first.acquire(Collections.singletonList(RateRule.cooldown(p,2000))).isAllowed());
            long after=System.nanoTime();
            assertFalse(second.acquire(Collections.singletonList(RateRule.cooldown(p,2000))).isAllowed());
            long ttl=redis.getExpire(p,java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue(ttl>0 && ttl<=2000);
            // 若实现错误缩短到约100ms，下面持续探测会捕获过早放行，而非只等键消失。
            long probeUntil=after+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2200);
            boolean allowedAgain=false;
            while(System.nanoTime()<probeUntil) {
                long started=System.nanoTime();
                boolean allowed=second.acquire(Collections.singletonList(RateRule.cooldown(p,2000))).isAllowed();
                long ended=System.nanoTime();
                if(allowed) {
                    // 网络执行时间未知，只断言最早可能的服务器时间，不做恰好毫秒命中的断言。
                    assertTrue(ended-before>=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2000),"冷却提前放行");
                    allowedAgain=true;break;
                }
                assertTrue(System.nanoTime()>=started);Thread.sleep(10);
            }
            assertTrue(allowedAgain,"正常两秒冷却应在有界探测内恢复");
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(Boolean.TRUE.equals(redis.hasKey(p)) && System.nanoTime()<until) Thread.sleep(10);
            assertFalse(redis.hasKey(p));
            assertTrue(second.acquire(Collections.singletonList(RateRule.cooldown(p,2000))).isAllowed());
        }
        @Test void denialLeavesExistingValuesAndTtlsUnchanged() {
            String v=key("existing-v"),u=key("existing-u"),pair=key("existing-pair");
            holdHash(v,0,8);holdHash(u,0,5);redis.opsForValue().set(pair,"2900",60,java.util.concurrent.TimeUnit.SECONDS);
            java.util.Map<Object,Object> beforeV=redis.opsForHash().entries(v),beforeU=redis.opsForHash().entries(u);
            long ttlV=redis.getExpire(v,java.util.concurrent.TimeUnit.MILLISECONDS),ttlU=redis.getExpire(u,java.util.concurrent.TimeUnit.MILLISECONDS),ttlP=redis.getExpire(pair,java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals(1L,at("rate_limit",100,Arrays.asList(v,u,pair),"FIXED_WINDOW","100","1000","FIXED_WINDOW","5","10000","COOLDOWN","1","2000").getResult().get(0));
            assertEquals(beforeV,redis.opsForHash().entries(v));assertEquals(beforeU,redis.opsForHash().entries(u));assertEquals("2900",redis.opsForValue().get(pair));
            assertTrue(redis.getExpire(v,java.util.concurrent.TimeUnit.MILLISECONDS)<=ttlV);assertTrue(redis.getExpire(u,java.util.concurrent.TimeUnit.MILLISECONDS)<=ttlU);assertTrue(redis.getExpire(pair,java.util.concurrent.TimeUnit.MILLISECONDS)<=ttlP);
        }
        @Test void corruptFieldsAndMissingTtlNeverPartiallyWrite() {
            String bad=key("corrupt"),other=key("other");
            redis.opsForHash().put(bad,"count","not-a-number");redis.expire(bad,60,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(3L,at("rate_limit",100,Arrays.asList(other,bad),"FIXED_WINDOW","100","1000","FIXED_WINDOW","5","10000").getResult().get(0));
            assertFalse(redis.hasKey(other));
            holdHash(bad,0,2);redis.persist(bad);
            assertEquals(3L,at("rate_limit",100,Collections.singletonList(bad),"FIXED_WINDOW","5","10000").getResult().get(0));
        }
        @Test void concurrentNativeTimeAdmissionsNeverExceedEachWindow() throws Exception {
            String k=key("concurrent");java.util.List<String> keys=Collections.singletonList(k);
            java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(16);
            java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<LuaTimeHarness.Probe>> futures=new java.util.ArrayList<>();
            try {
                for(int i=0;i<200;i++)futures.add(pool.submit(()->{start.await();return new LuaTimeHarness(redis).evalRecorded("lua/protection/rate_limit.lua",keys,Arrays.asList("FIXED_WINDOW","100","1000"));}));
                start.countDown();java.util.Map<Long,Integer> admitted=new java.util.HashMap<>();
                for(java.util.concurrent.Future<LuaTimeHarness.Probe> f:futures){
                    LuaTimeHarness.Probe probe=f.get(15,java.util.concurrent.TimeUnit.SECONDS);
                    long status=probe.getResult().get(0);assertTrue(status==0 || status==1);
                    if(status==0)admitted.merge(probe.getObservedTimeMs()/1000,1,Integer::sum);
                }
                assertFalse(admitted.isEmpty());for(int count:admitted.values())assertTrue(count<=100);
            }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
