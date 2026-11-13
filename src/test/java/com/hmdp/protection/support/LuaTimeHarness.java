package com.hmdp.protection.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** 原样包裹生产脚本，仅在测试中替换 TIME，且在同一次执行内捕获短 TTL 状态。 */
public final class LuaTimeHarness {
    private final StringRedisTemplate redis;
    /**
     * @param redis 明确连接隔离环境的客户端
     */
    public LuaTimeHarness(StringRedisTemplate redis) { this.redis = redis; }

    /**
     * 执行虚拟时间用例；真实 Redis 仍执行除 TIME 外的全部操作。
     * @param resource 生产脚本资源
     * @param keys 本次隔离 Key
     * @param args 脚本原始参数
     * @param nowMs 测试时间，毫秒
     * @return 同次调用快照
     */
    public Probe evalAt(String resource, List<String> keys, List<String> args, long nowMs) {
        return eval(resource,keys,args,nowMs);
    }

    /**
     * 不替换真实时间，只记录生产脚本取到的时刻，供跨窗口并发结果分组。
     * @param resource 原生产资源
     * @param keys 隔离 Key
     * @param args 原参数
     * @return 包含实际脚本时间的快照
     */
    public Probe evalRecorded(String resource,List<String> keys,List<String> args) {
        return eval(resource,keys,args,null);
    }

    private Probe eval(String resource,List<String> keys,List<String> args,Long nowMs) {
        String source;
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            source = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("生产脚本资源未就绪", e); }
        String time=nowMs==null ? "native.call(cmd, ...)" : "{'"+nowMs/1000+"','"+nowMs%1000*1000+"'}";
        String wrapper = "local native=redis local observed=0\nlocal redis={call=function(cmd, ...) "
                + "if cmd=='TIME' then local t="+time+" observed=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000) return t end "
                + "return native.call(cmd, ...) end}\nlocal function run()\n" + source
                + "\nend\nlocal result=run() local values={} local ttls={} "
                + "for i,k in ipairs(KEYS) do local t=native.call('TYPE',k).ok "
                + "if t=='hash' then values[i]=native.call('HGETALL',k) "
                + "elseif t=='string' then values[i]=native.call('GET',k) else values[i]=false end "
                + "ttls[i]=native.call('PTTL',k) end return {result,values,ttls,observed}";
        List<?> result = redis.execute(new DefaultRedisScript<>(wrapper, List.class), keys, args.toArray());
        return new Probe(keys, result);
    }

    /** 包装脚本结果，不向生产代码开放任何测试时钟。 */
    public static final class Probe {
        private final List<Long> result;
        private final Map<String, Object> values = new HashMap<>();
        private final Map<String, Long> ttls = new HashMap<>();
        private final long observedTimeMs;
        @SuppressWarnings("unchecked") private Probe(List<String> keys, List<?> raw) {
            result = (List<Long>) raw.get(0);
            observedTimeMs=((Number)raw.get(3)).longValue();
            List<?> v = (List<?>) raw.get(1), t = (List<?>) raw.get(2);
            for (int i=0;i<keys.size();i++) { values.put(keys.get(i),v.get(i)); ttls.put(keys.get(i),((Number)t.get(i)).longValue()); }
        }
        /**
         * @return Lua 状态及等待时间
         */
        public List<Long> getResult() { return result; }
        /**
         * @param key 隔离 Key
         * @return String 或 Hash 的字段值列表，缺失时为 null
         */
        public Object getValue(String key) { return values.get(key); }
        /**
         * @param key 隔离 Key
         * @return 同次执行时的 PTTL
         */
        public long getPttlMs(String key) { return ttls.get(key); }
        /**
         * @return 生产脚本实际使用的 Redis 时间
         */
        public long getObservedTimeMs() { return observedTimeMs; }
    }
}
