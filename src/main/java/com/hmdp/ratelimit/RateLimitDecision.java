package com.hmdp.ratelimit;

import lombok.Getter;

/** 原子准入结果；错误状态不作为普通拒绝结果返回。 */
@Getter
public final class RateLimitDecision {
    private final boolean allowed;
    private final long retryAfterMs;
    /**
     * @param allowed 是否准入
     * @param retryAfterMs 拒绝时的等待时间
     */
    public RateLimitDecision(boolean allowed, long retryAfterMs) { this.allowed=allowed; this.retryAfterMs=allowed?0:retryAfterMs; }
}
