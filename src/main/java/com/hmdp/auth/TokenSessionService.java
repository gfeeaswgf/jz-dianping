package com.hmdp.auth;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.ProtectionUnavailableException;
import com.hmdp.utils.ProtectionKeys;
import com.hmdp.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;


@Component
public class TokenSessionService {

    private final StringRedisTemplate redis;
    private final ProtectionProperties properties;
    private final ProtectionKeys keys;

    // 创建Token的Lua脚本
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>();

    // 刷新Token的Lua脚本
    private static final DefaultRedisScript<List> REFRESH = new DefaultRedisScript<>();

    // 加载Lua脚本
    static {
        CREATE.setLocation(new ClassPathResource("lua/protection/token_create.lua"));
        CREATE.setResultType(Long.class);

        REFRESH.setLocation(new ClassPathResource("lua/protection/token_refresh.lua"));
        REFRESH.setResultType(List.class);
    }

    // 构造方法注入
    public TokenSessionService(StringRedisTemplate redis,
                               ProtectionProperties properties,
                               ProtectionKeys keys) {
        this.redis = redis;
        this.properties = properties;
        this.keys = keys;
    }

    /**
     * 创建用户Token并保存登录状态
     * @param user 用户信息
     * @return Token
     */
    public String create(UserDTO user) {

        // 1. 校验用户信息
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new ProtectionUnavailableException("TOKEN_USER_INVALID");
        }

        // 2. 生成随机Token
        String token = UUID.randomUUID().toString().replace("-", "");

        // 3. 创建Lua脚本参数集合
        List<String> args = new ArrayList<>();

        // 3.1 添加Token过期时间
        args.add(ttl());

        // 3.2 添加用户ID
        args.add("id");
        args.add(user.getId().toString());

        // 3.3 添加用户昵称
        if (user.getNickName() != null) {
            args.add("nickName");
            args.add(user.getNickName());
        }

        // 3.4 添加用户头像
        if (user.getIcon() != null) {
            args.add("icon");
            args.add(user.getIcon());
        }

        // 4. 生成Redis Key
        String key = keys.key(LOGIN_USER_KEY, token);

        // 5. 执行Lua脚本，保存用户信息并设置TTL
        Long reply;

        try {
            reply = redis.execute(
                    CREATE,
                    Collections.singletonList(key),
                    args.toArray()
            );
        } catch (RuntimeException e) {
            throw new ProtectionUnavailableException("TOKEN_CREATE_FAILURE");
        }

        // 6. 判断Token创建是否成功
        if (reply == null || reply != 1L) {
            throw new ProtectionUnavailableException("TOKEN_CREATE_STATE");
        }

        // 7. 返回Token
        return token;
    }

    /**
     * 根据Token获取用户信息并刷新有效期
     * @param token 用户Token
     * @return 用户信息
     */
    public UserDTO resolveAndRefresh(String token) {

        // 1. 校验Token格式
        if (!valid(token)) {
            return null;
        }

        // 2. 生成Redis Key
        String key = keys.key(LOGIN_USER_KEY, token);

        // 3. 执行Lua脚本，读取用户信息并刷新TTL
        List<?> reply;

        try {
            reply = redis.execute(
                    REFRESH,
                    Collections.singletonList(key),
                    ttl()
            );
        } catch (RuntimeException e) {
            throw new ProtectionUnavailableException("TOKEN_REFRESH_FAILURE");
        }

        // 4. 判断Lua返回结果是否合法
        if (reply == null || reply.size() % 2 != 0) {
            throw new ProtectionUnavailableException("TOKEN_REPLY_INVALID");
        }

        // 5. Token不存在或已经过期
        if (reply.isEmpty()) {
            return null;
        }

        // 6. 创建用户对象和字段集合
        UserDTO user = new UserDTO();
        Set<String> seen = new HashSet<>();

        // 7. 遍历Redis返回的用户信息
        try {
            for (int i = 0; i < reply.size(); i += 2) {

                // 7.1 校验字段和值的类型
                if (!(reply.get(i) instanceof String)
                        || !(reply.get(i + 1) instanceof String)) {
                    throw new IllegalArgumentException();
                }

                // 7.2 获取字段名和字段值
                String field = (String) reply.get(i);
                String value = (String) reply.get(i + 1);

                // 7.3 判断字段是否重复
                if (!seen.add(field)) {
                    throw new IllegalArgumentException();
                }

                // 7.4 将Redis数据封装到UserDTO
                if ("id".equals(field)) {
                    user.setId(Long.valueOf(value));
                } else if ("nickName".equals(field)) {
                    user.setNickName(value);
                } else if ("icon".equals(field)) {
                    user.setIcon(value);
                } else {
                    throw new IllegalArgumentException();
                }
            }

            // 8. 校验用户ID
            if (user.getId() == null || user.getId() <= 0) {
                throw new IllegalArgumentException();
            }

        } catch (RuntimeException e) {
            throw new ProtectionUnavailableException("TOKEN_HASH_INVALID");
        }

        // 9. 返回用户信息
        return user;
    }

    /**
     * 用户退出登录
     * @param token 用户Token
     */
    public void logout(String token) {

        try {
            // 1. 校验Token格式
            if (valid(token)) {

                // 2. 生成Redis Key
                String key = keys.key(LOGIN_USER_KEY, token);

                // 3. 删除Redis中的登录信息
                Boolean result = redis.delete(key);

                // 4. 判断Redis操作结果
                if (result == null) {
                    throw new ProtectionUnavailableException("TOKEN_DELETE_STATE");
                }
            }

        } catch (RuntimeException e) {
            throw new ProtectionUnavailableException("TOKEN_DELETE_FAILURE");

        } finally {
            // 5. 清理当前线程保存的用户信息
            UserHolder.removeUser();
        }
    }

    /**
     * 校验Token格式
     */
    private boolean valid(String token) {

        // Token必须是32位十六进制字符串
        return token != null && token.matches("[0-9a-fA-F]{32}");
    }

    /**
     * 获取Token过期时间（毫秒）
     */
    private String ttl() {

        // 1. 获取配置的Token闲置过期时间（分钟）
        long minutes = properties.getTokenIdleTtlMinutes();

        // 2. 将分钟转换为毫秒
        long milliseconds = TimeUnit.MINUTES.toMillis(minutes);

        // 3. 转换为字符串
        return String.valueOf(milliseconds);
    }
}
