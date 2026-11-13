package com.hmdp.protection.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 只连接显式配置的测试 Redis；登记隔离 Key 并逐项清理，绝不清空数据库。 */
public abstract class RedisProtectionTestSupport {
    protected StringRedisTemplate redis;
    protected String prefix;
    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private final Set<String> ownedKeys = new LinkedHashSet<>();

    @BeforeEach protected void openIsolatedRedis() {
        org.springframework.boot.logging.LoggingSystem logs=org.springframework.boot.logging.LoggingSystem.get(getClass().getClassLoader());
        logs.setLogLevel("org.springframework",org.springframework.boot.logging.LogLevel.INFO);
        logs.setLogLevel("io.lettuce",org.springframework.boot.logging.LogLevel.INFO);
        prefix = "test:protection:" + UUID.randomUUID().toString().replace("-", "") + ":";
        redis = newClient();
        redis.getConnectionFactory().getConnection().close();
    }

    protected StringRedisTemplate newClient() {
        RedisStandaloneConfiguration c = isolatedConfiguration();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(c,
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet();
        factories.add(factory);
        return new StringRedisTemplate(factory);
    }

    /**
     * 只读复用显式选定的 YAML 连接，测试逻辑库必须单独指定且不同于业务库。
     * 未选择 YAML 模式时兼容原显式测试环境变量，不自动读取业务连接。
     * @return 尚未连接的隔离 Redis 配置
     * @throws IllegalStateException 选择不明确、库冲突或文件格式无效时抛出，不包含敏感原文
     */
    public static RedisStandaloneConfiguration isolatedConfiguration() {
        String path=System.getProperty("hmdp.test.redis.config");
        String selected=System.getProperty("hmdp.test.redis.database");
        if(path!=null || selected!=null) {
            if(path==null || selected==null)throw new IllegalStateException("测试 YAML 与独立逻辑库参数必须同时明确指定");
            Map<?,?> values;
            try(InputStream input=Files.newInputStream(Paths.get(path))) {
                Object root=new Yaml(new SafeConstructor()).load(input);
                Object spring=((Map<?,?>)root).get("spring");
                values=(Map<?,?>)((Map<?,?>)spring).get("redis");
                if(values==null)throw new IllegalArgumentException();
            }catch(Exception e){throw new IllegalStateException("无法读取明确指定的 Redis 测试连接配置，未输出敏感原文");}
            try {
                int database=Integer.parseInt(selected);
                int business=values.get("database")==null?0:Integer.parseInt(values.get("database").toString());
                if(database<0 || database>15 || database==business)throw new IllegalStateException("测试库必须在0至15之间且不能与业务逻辑库相同");
                Object rawHost=values.get("host");String host=rawHost==null?"":rawHost.toString();
                if(host.trim().isEmpty() || host.contains("${"))throw new IllegalArgumentException();
                int port=values.get("port")==null?6379:Integer.parseInt(values.get("port").toString());
                if(port<1 || port>65535)throw new IllegalArgumentException();
                RedisStandaloneConfiguration c=new RedisStandaloneConfiguration(host,port);c.setDatabase(database);
                Object password=values.get("password");
                if(password!=null && !password.toString().isEmpty())c.setPassword(RedisPassword.of(password.toString()));
                Object username=values.get("username");if(username!=null)c.setUsername(username.toString());
                return c;
            }catch(IllegalStateException e){throw e;}
            catch(RuntimeException e){throw new IllegalStateException("隔离 Redis 配置参数无效，未输出敏感原文");}
        }
        RedisStandaloneConfiguration c=new RedisStandaloneConfiguration(required("HOST"),Integer.parseInt(required("PORT")));
        c.setDatabase(Integer.parseInt(required("DATABASE")));
        String password=System.getenv("HMDP_TEST_REDIS_PASSWORD");
        if(password!=null && !password.isEmpty())c.setPassword(RedisPassword.of(password));
        return c;
    }

    private static String required(String name) {
        String value = System.getenv("HMDP_TEST_REDIS_" + name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("隔离 Redis 未配置：HMDP_TEST_REDIS_HOST/PORT/DATABASE；未执行真实 Lua 测试");
        }
        return value;
    }

    protected String key(String suffix) {
        String key = prefix + suffix;
        ownedKeys.add(key);
        return key;
    }

    protected void holdHash(String key, long windowId, long count) {
        Map<String, String> data = new HashMap<>();
        data.put("windowId", String.valueOf(windowId)); data.put("count", String.valueOf(count));
        redis.opsForHash().putAll(key, data);
        redis.expire(key, 60, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    protected List<Long> raw(String name, List<String> keys, String... args) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("lua/protection/" + name + ".lua"));
        script.setResultType(List.class);
        return (List<Long>) redis.execute(script, keys, (Object[]) args);
    }

    protected LuaTimeHarness.Probe at(String name, long now, List<String> keys, String... args) {
        return new LuaTimeHarness(redis).evalAt("lua/protection/" + name + ".lua", keys, Arrays.asList(args), now);
    }

    protected void expireAndAwait(String key) throws InterruptedException {
        redis.expire(key, 1, TimeUnit.MILLISECONDS);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < end) Thread.sleep(5);
        org.junit.jupiter.api.Assertions.assertFalse(Boolean.TRUE.equals(redis.hasKey(key)), "测试 Key 未在限定时间过期");
    }

    @AfterEach protected void closeIsolatedRedis() {
        try {
            if (redis != null) for (String key : ownedKeys) {
                if (!key.startsWith(prefix)) throw new IllegalStateException("拒绝清理非本次测试 Key");
                redis.delete(key);
            }
        } finally { for (LettuceConnectionFactory f : factories) f.destroy(); }
    }
}
