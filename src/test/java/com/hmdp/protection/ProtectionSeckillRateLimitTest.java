package com.hmdp.protection;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.ratelimit.*;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 只验证秒杀入口准入，不启动或改变原库存与异步订单业务。 */
class ProtectionSeckillRateLimitTest {
    private RedisRateLimiter limiter;
    private IVoucherOrderService orders;
    private MockMvc mvc;
    @BeforeEach void setup() {
        limiter=mock(RedisRateLimiter.class); orders=mock(IVoucherOrderService.class);
        when(limiter.acquire(anyList())).thenReturn(new RateLimitDecision(true,0));
        when(orders.seckillVoucher(anyLong())).thenReturn(Result.ok(123L));
        VoucherOrderController target=new VoucherOrderController(); ReflectionTestUtils.setField(target,"voucherOrderService",orders);
        mvc=MockMvcBuilders.standaloneSetup(ProtectionRateLimitAspectTest.proxy(target,limiter,new ProtectionProperties()))
                .addInterceptors(new LoginInterceptor()).setControllerAdvice(new WebExceptionAdvice()).build();
        UserDTO u=new UserDTO();u.setId(42L);UserHolder.saveUser(u);
    }
    @AfterEach void cleanIdentity() { UserHolder.removeUser(); }

    @Test void blockedDoesNotGenerateOrSubmitOrder() throws Exception {
        when(limiter.acquire(anyList())).thenReturn(new RateLimitDecision(false,2000));
        mvc.perform(post("/voucher-order/seckill/7")).andExpect(status().isTooManyRequests());
        verifyNoInteractions(orders);
    }
    @Test void allowedDelegatesExactlyOnceAndPreservesResponse() throws Exception {
        mvc.perform(post("/voucher-order/seckill/7")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(123));
        verify(orders,times(1)).seckillVoucher(7L);
        verify(limiter,times(1)).acquire(anyList());
    }
    @Test void seckillUsesAuthenticatedUserAndPathVoucher() throws Exception {
        mvc.perform(post("/voucher-order/seckill/7").param("userId","9999")).andExpect(status().isOk());
        ArgumentCaptor<List<RateRule>> captor=ArgumentCaptor.forClass(List.class);verify(limiter).acquire(captor.capture());
        List<RateRule> rules=captor.getValue();
        assertEquals("rl:seckill:v:7",rules.get(0).getKey()); assertEquals(100,rules.get(0).getLimit());assertEquals(1000,rules.get(0).getDurationMs());
        assertEquals("rl:seckill:u:42",rules.get(1).getKey()); assertEquals(5,rules.get(1).getLimit());assertEquals(10000,rules.get(1).getDurationMs());
        assertEquals("rl:seckill:uv:42:7",rules.get(2).getKey());assertEquals(RateRule.Kind.COOLDOWN,rules.get(2).getKind());assertEquals(2000,rules.get(2).getDurationMs());
    }
    @Test void differentVouchersAndTokensShareUserButHaveIndependentPair() throws Exception {
        mvc.perform(post("/voucher-order/seckill/7").header("authorization","first"));
        mvc.perform(post("/voucher-order/seckill/8").header("authorization","second"));
        ArgumentCaptor<List<RateRule>> c=ArgumentCaptor.forClass(List.class);verify(limiter,times(2)).acquire(c.capture());
        assertEquals(c.getAllValues().get(0).get(1).getKey(),c.getAllValues().get(1).get(1).getKey());
        assertNotEquals(c.getAllValues().get(0).get(2).getKey(),c.getAllValues().get(1).get(2).getKey());
    }
    @Test void anonymousProtectedRequestIs401BeforeLimiter() throws Exception {
        UserHolder.removeUser();mvc.perform(post("/voucher-order/seckill/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(limiter,orders);
    }
    @Test void invalidVoucherIs400BeforeRedisOrOrder() throws Exception {
        mvc.perform(post("/voucher-order/seckill/0")).andExpect(status().isBadRequest());
        verifyNoInteractions(limiter,orders);
    }
}
