package com.hmdp.ratelimit;

/** 可复用接口准入策略，额度和时间由 ProtectionProperties 管理。 */
public enum RateLimitPolicy { SMS_SEND_IP, LOGIN_IP, SECKILL }
