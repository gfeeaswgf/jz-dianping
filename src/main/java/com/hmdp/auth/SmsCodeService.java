package com.hmdp.auth;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.exception.*;
import com.hmdp.utils.ProtectionKeys;
import com.hmdp.utils.RegexUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.*;
import static com.hmdp.utils.RedisConstants.*;


@Component
public class SmsCodeService {

    private final StringRedisTemplate redis;
    private final ProtectionProperties p;
    private final ProtectionKeys keys;
    private final SmsSender sender;

    // 生成安全随机验证码
    private static final SecureRandom RANDOM = new SecureRandom();

    // 加载短信发送预占Lua脚本
    private static final DefaultRedisScript<List> RESERVE =
            script("sms_reserve", List.class);

    // 加载短信发送失败清理Lua脚本
    private static final DefaultRedisScript<Long> CLEANUP =
            script("sms_cleanup", Long.class);

    // 加载验证码校验Lua脚本
    private static final DefaultRedisScript<List> VERIFY =
            script("sms_verify", List.class);

    // 构造方法注入
    public SmsCodeService(StringRedisTemplate redis,
                          ProtectionProperties p,
                          ProtectionKeys keys,
                          SmsSender sender) {
        this.redis = redis;
        this.p = p;
        this.keys = keys;
        this.sender = sender;
    }

    /**
     * 发送短信验证码
     * @param phone 手机号
     */
    public void sendCode(String phone) {

        // 1. 校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new ProtectionInvalidRequestException();
        }

        // 2. 生成6位随机验证码
        String code = String.format(
                Locale.ROOT,
                "%06d",
                RANDOM.nextInt(1000000)
        );

        // 3. 生成本次验证码的唯一签发ID
        String issue = UUID.randomUUID()
                .toString()
                .replace("-", "");

        // 4. 创建Redis Key集合
        List<String> stateKeys = Arrays.asList(
                keys.key(LOGIN_CODE_KEY, phone),
                keys.key(SMS_ISSUE_KEY, phone),
                keys.key(SMS_COOLDOWN_KEY, phone),
                keys.key(SMS_HOUR_KEY, phone),
                keys.key(SMS_DAY_KEY, phone),
                keys.key(VERIFY_LOCK_KEY, phone)
        );

        // 5. 执行Lua脚本，预占短信发送额度
        List<Long> result = execute(
                RESERVE,
                stateKeys,
                code,
                issue,
                String.valueOf(p.getSmsCodeTtlMs()),
                String.valueOf(p.getSmsCooldownMs()),
                String.valueOf(p.getSmsHourLimit()),
                String.valueOf(p.getSmsDayLimit())
        );

        // 6. 判断是否触发短信限流
        if (result.get(0) == 1L) {
            throw new ProtectionRejectedException(result.get(1));
        }

        // 7. 判断Redis返回状态是否正常
        if (result.get(0) != 0L) {
            throw new ProtectionUnavailableException("SMS_RESERVE_STATE");
        }

        // 8. 调用短信发送服务
        SmsSendOutcome outcome;

        try {
            outcome = sender.send(phone, code, issue);
        } catch (RuntimeException e) {
            // 发送结果未知
            outcome = SmsSendOutcome.UNKNOWN;
        }

        // 9. 短信发送成功，直接返回
        if (outcome == SmsSendOutcome.ACCEPTED) {
            return;
        }

        // 10. 短信明确发送失败，清理本次验证码
        if (outcome == SmsSendOutcome.REJECTED) {

            Long cleaned;

            try {
                // 只清理与本次签发ID对应的验证码
                cleaned = redis.execute(
                        CLEANUP,
                        stateKeys.subList(0, 2),
                        issue
                );
            } catch (RuntimeException e) {
                throw new ProtectionUnavailableException("SMS_CLEANUP_FAILURE");
            }

            // 10.1 判断清理结果是否合法
            if (cleaned == null || (cleaned != 0L && cleaned != 1L)) {
                throw new ProtectionUnavailableException("SMS_CLEANUP_STATE");
            }
        }

        // 11. 短信发送失败或发送结果未知
        throw new ProtectionUnavailableException("SMS_DELIVERY_UNAVAILABLE");
    }

    /**
     * 校验并消费验证码
     * @param phone 手机号
     * @param code 验证码
     * @return 验证码是否正确
     */
    public boolean verifyAndConsume(String phone, String code) {

        // 1. 校验手机号和验证码格式
        if (RegexUtils.isPhoneInvalid(phone)
                || code == null
                || !code.matches("[0-9]{6}")) {
            throw new ProtectionInvalidRequestException();
        }

        // 2. 创建验证码校验所需的Redis Key
        List<String> stateKeys = Arrays.asList(
                keys.key(LOGIN_CODE_KEY, phone),
                keys.key(SMS_ISSUE_KEY, phone),
                keys.key(VERIFY_FAIL_KEY, phone),
                keys.key(VERIFY_LOCK_KEY, phone)
        );

        // 3. 执行Lua脚本校验验证码
        List<Long> result = execute(
                VERIFY,
                stateKeys,
                code,
                String.valueOf(p.getVerifyFailureLimit()),
                String.valueOf(p.getVerifyFailureWindowMs()),
                String.valueOf(p.getVerifyLockMs())
        );

        // 4. 判断手机号是否被锁定
        if (result.get(0) == 1L) {
            throw new ProtectionRejectedException(result.get(1));
        }

        // 5. 返回验证码校验结果
        return result.get(0) == 0L;
    }

    /**
     * 执行Redis Lua脚本
     * @param script Lua脚本
     * @param stateKeys Redis Key集合
     * @param args Lua脚本参数
     * @return Lua脚本执行结果
     */
    @SuppressWarnings("unchecked")
    private List<Long> execute(DefaultRedisScript<List> script,
                               List<String> stateKeys,
                               Object... args) {

        // 1. 执行Redis Lua脚本
        List<?> reply;

        try {
            reply = redis.execute(script, stateKeys, args);
        } catch (RuntimeException e) {
            throw new ProtectionUnavailableException("SMS_REDIS_FAILURE");
        }

        // 2. 判断Lua返回结果是否合法
        if (reply == null
                || reply.size() != 2
                || !(reply.get(0) instanceof Long)
                || !(reply.get(1) instanceof Long)) {
            throw new ProtectionUnavailableException("SMS_REPLY_INVALID");
        }

        // 3. 获取返回状态和等待时间
        long status = (Long) reply.get(0);
        long wait = (Long) reply.get(1);

        // 4. 判断返回状态是否合法
        boolean validSuccess = (status == 0 || status == 2) && wait == 0;

        boolean validRejected = status == 1
                && wait > 0
                && wait <= 86400000L;

        if (!validSuccess && !validRejected) {
            throw new ProtectionUnavailableException("SMS_STATE_INVALID");
        }

        // 5. 返回Lua执行结果
        return (List<Long>) reply;
    }

    /**
     * 加载Redis Lua脚本
     * @param name 脚本名称
     * @param type 返回值类型
     * @return Lua脚本对象
     */
    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {

        // 1. 创建Lua脚本对象
        DefaultRedisScript<T> script = new DefaultRedisScript<>();

        // 2. 设置Lua脚本路径
        script.setLocation(
                new ClassPathResource("lua/protection/" + name + ".lua")
        );

        // 3. 设置Lua脚本返回类型
        script.setResultType(type);

        // 4. 返回Lua脚本对象
        return script;
    }
}
