package com.hmdp.exception;

/** 额度、冷却或临时锁定拒绝，只携带等待时间，不携带敏感 Key。 */
public class ProtectionRejectedException extends RuntimeException {
    private final long retryAfterMs;
    /**
     * @param retryAfterMs 再次尝试至少需要等待的毫秒数
     */
    public ProtectionRejectedException(long retryAfterMs) {
        super("请求过于频繁，请稍后重试");
        this.retryAfterMs = Math.max(1, retryAfterMs);
    }
    /**
     * @return 等待毫秒数
     */
    public long getRetryAfterMs() { return retryAfterMs; }
}
