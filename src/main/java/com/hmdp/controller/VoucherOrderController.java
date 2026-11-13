package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.ratelimit.RateLimit;
import com.hmdp.ratelimit.RateLimitPolicy;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 优惠券秒杀入口；仅在 Controller 执行准入，不改变原有库存与异步订单逻辑。
 * </p>
 *
 * @author jz
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 三维准入通过后提交原秒杀业务。
     * @param voucherId 正数优惠券 ID，用户身份由服务端登录上下文提供
     * @return 原业务的订单 ID 或业务失败结果
     */
    @PostMapping("seckill/{id}")
    @RateLimit(policy = RateLimitPolicy.SECKILL)
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
