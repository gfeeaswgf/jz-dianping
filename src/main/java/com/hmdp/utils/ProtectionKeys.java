package com.hmdp.utils;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.exception.ProtectionInvalidRequestException;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 集中构造防刷 Key，隔离测试前缀与生产业务前缀，禁止任意命名空间。 */
@Component
public class ProtectionKeys {
    private final ProtectionProperties properties;
    private static final Set<String> BASES = new HashSet<>(Arrays.asList(
            RedisConstants.LOGIN_CODE_KEY,RedisConstants.LOGIN_USER_KEY,RedisConstants.SMS_ISSUE_KEY,
            RedisConstants.SMS_COOLDOWN_KEY,RedisConstants.SMS_HOUR_KEY,RedisConstants.SMS_DAY_KEY,
            RedisConstants.VERIFY_FAIL_KEY,RedisConstants.VERIFY_LOCK_KEY,RedisConstants.RATE_SMS_IP_KEY,
            RedisConstants.RATE_LOGIN_IP_KEY,RedisConstants.RATE_SECKILL_VOUCHER_KEY,
            RedisConstants.RATE_SECKILL_USER_KEY,RedisConstants.RATE_SECKILL_PAIR_KEY));
    /** @param properties 已校验的配置 */
    public ProtectionKeys(ProtectionProperties properties) { this.properties = properties; }
    /**
     * 生成受控业务 Key；调用者仍需做手机号、ID、Token 等业务格式校验。
     * @param basePrefix RedisConstants 中的合法前缀
     * @param segments 已规范化的维度
     * @return 完整 Key
     * @throws ProtectionInvalidRequestException 前缀或维度非法时抛出
     */
    public String key(String basePrefix, String... segments) {
        if (!BASES.contains(basePrefix) || segments == null || segments.length == 0) throw new ProtectionInvalidRequestException();
        for (String part : segments) if (part == null || !part.matches("[A-Za-z0-9:._-]{1,128}")) throw new ProtectionInvalidRequestException();
        return properties.getKeyPrefix() + basePrefix + String.join(":", segments);
    }
}
