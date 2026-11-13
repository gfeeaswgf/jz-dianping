package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/** 登录防刷的配置默认值；不要求修改用户已有 YAML，时间属性明确以毫秒或分钟为单位。 */
@Data
@Component
@ConfigurationProperties(prefix = "protection")
public class ProtectionProperties {
    private String keyPrefix = "";
    private long seckillVoucherLimit = 100;
    private long seckillVoucherWindowMs = 1000;
    private long seckillUserLimit = 5;
    private long seckillUserWindowMs = 10000;
    private long seckillUserVoucherCooldownMs = 2000;
    private long smsIpLimit = 20;
    private long loginIpLimit = 60;
    private long ipWindowMs = 60000;
    private long smsCodeTtlMs = 120000;
    private long smsCooldownMs = 60000;
    private long smsHourLimit = 5;
    private long smsDayLimit = 10;
    private long verifyFailureLimit = 5;
    private long verifyFailureWindowMs = 900000;
    private long verifyLockMs = 900000;
    private long tokenIdleTtlMinutes = 36000;
    private String smsMode = "mock";
    private List<String> trustedProxyAddresses = new ArrayList<>();

    /**
     * 启动时拒绝无效保护参数，防止单位错误或精度溢出变成保护失效。
     * @throws IllegalArgumentException 参数越界、命名空间不受控或短信模式未知时抛出
     */
    @PostConstruct
    public void validate() {
        long[] durations = {seckillVoucherWindowMs,seckillUserWindowMs,seckillUserVoucherCooldownMs,
                ipWindowMs,smsCodeTtlMs,smsCooldownMs,verifyFailureWindowMs,verifyLockMs};
        long[] limits = {seckillVoucherLimit,seckillUserLimit,smsIpLimit,loginIpLimit,smsHourLimit,smsDayLimit,verifyFailureLimit};
        for (long n : durations) if (n <= 0 || n > 86400000L) throw new IllegalArgumentException("保护时间范围无效");
        for (long n : limits) if (n <= 0 || n > 1000000L) throw new IllegalArgumentException("保护额度范围无效");
        if (tokenIdleTtlMinutes <= 0 || tokenIdleTtlMinutes > 525600L) throw new IllegalArgumentException("登录有效期范围无效");
        if (keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9:_-]{0,128}")
                || (!keyPrefix.isEmpty() && !keyPrefix.endsWith(":"))) throw new IllegalArgumentException("保护命名空间无效");
        if (!"mock".equals(smsMode) || trustedProxyAddresses == null) throw new IllegalArgumentException("保护模式配置无效");
        // 不改用户 YAML；仅抑制会记录完整手机号的 Mapper SQL 参数，其他业务日志级别不动。
        org.springframework.boot.logging.LoggingSystem.get(ProtectionProperties.class.getClassLoader())
                .setLogLevel("com.hmdp.mapper.UserMapper", org.springframework.boot.logging.LogLevel.INFO);
    }
}
