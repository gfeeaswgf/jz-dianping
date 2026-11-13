package com.hmdp.ratelimit;

import com.hmdp.exception.ProtectionInvalidRequestException;
import lombok.Getter;

/** 一条不可变准入规则；固定窗口统计次数，冷却记录上次放行后的截止时刻。 */
@Getter
public final class RateRule {
    /** 两种算法必须区分，避免把严格冷却错误实现成时间桶。 */
    public enum Kind { FIXED_WINDOW, COOLDOWN }
    private final String key;
    private final Kind kind;
    private final long limit;
    private final long durationMs;
    private RateRule(String key, Kind kind, long limit, long durationMs) {
        if (key == null || key.isEmpty() || key.length() > 512 || limit <= 0 || limit > 1000000
                || durationMs <= 0 || durationMs > 86400000) throw new ProtectionInvalidRequestException();
        this.key=key; this.kind=kind; this.limit=limit; this.durationMs=durationMs;
    }
    /**
     * @param key 受控 Key
     * @param limit 窗口额度
     * @param windowMs 窗口毫秒数
     * @return 固定窗口规则
     */
    public static RateRule fixedWindow(String key, long limit, long windowMs) { return new RateRule(key,Kind.FIXED_WINDOW,limit,windowMs); }
    /**
     * @param key 受控 Key
     * @param durationMs 两次放行之间的最短间隔
     * @return 严格冷却规则
     */
    public static RateRule cooldown(String key, long durationMs) { return new RateRule(key,Kind.COOLDOWN,1,durationMs); }
}
