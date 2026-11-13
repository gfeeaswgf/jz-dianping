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

/** 管理当前随机 Token 的 Hash、闲置续期和退出删除，不实现 JWT 或所有设备退出。 */
@Component
public class TokenSessionService {
    private final StringRedisTemplate redis;
    private final ProtectionProperties properties;
    private final ProtectionKeys keys;
    private static final DefaultRedisScript<Long> CREATE=new DefaultRedisScript<>();
    private static final DefaultRedisScript<List> REFRESH=new DefaultRedisScript<>();
    static {
        CREATE.setLocation(new ClassPathResource("lua/protection/token_create.lua"));CREATE.setResultType(Long.class);
        REFRESH.setLocation(new ClassPathResource("lua/protection/token_refresh.lua"));REFRESH.setResultType(List.class);
    }
    /**
     * @param redis 单实例客户端
     * @param properties 闲置 TTL 配置
     * @param keys 受控 Key 命名空间
     */
    public TokenSessionService(StringRedisTemplate redis,ProtectionProperties properties,ProtectionKeys keys) {this.redis=redis;this.properties=properties;this.keys=keys;}
    /**
     * 同一次脚本建立 Hash 与 TTL，避免进程中断留下永久登录凭证。
     * @param user 要保存的最小用户身份
     * @return 兼容原客户端的 32 位随机 Token
     * @throws ProtectionUnavailableException 写入异常或身份数据无效
     */
    public String create(UserDTO user) {
        if(user==null || user.getId()==null || user.getId()<=0) throw new ProtectionUnavailableException("TOKEN_USER_INVALID");
        String token=UUID.randomUUID().toString().replace("-","");
        List<String> args=new ArrayList<>(Arrays.asList(ttl(),"id",user.getId().toString()));
        if(user.getNickName()!=null){args.add("nickName");args.add(user.getNickName());}
        if(user.getIcon()!=null){args.add("icon");args.add(user.getIcon());}
        Long reply;
        try{reply=redis.execute(CREATE,Collections.singletonList(keys.key(LOGIN_USER_KEY,token)),args.toArray());}
        catch(RuntimeException e){throw new ProtectionUnavailableException("TOKEN_CREATE_FAILURE");}
        if(reply==null || reply!=1L)throw new ProtectionUnavailableException("TOKEN_CREATE_STATE");
        return token;
    }
    /**
     * 原子读取并续期；空结果不重建已退出凭证，格式无效时不查询 Redis。
     * @param token 原始 authorization 头值，不含 Bearer 前缀
     * @return 有效身份；缺失或已过期返回 null
     * @throws ProtectionUnavailableException Redis 或 Hash 状态异常
     */
    public UserDTO resolveAndRefresh(String token) {
        if(!valid(token))return null;
        List<?> reply;
        try{reply=redis.execute(REFRESH,Collections.singletonList(keys.key(LOGIN_USER_KEY,token)),ttl());}
        catch(RuntimeException e){throw new ProtectionUnavailableException("TOKEN_REFRESH_FAILURE");}
        if(reply==null || reply.size()%2!=0)throw new ProtectionUnavailableException("TOKEN_REPLY_INVALID");
        if(reply.isEmpty())return null;
        UserDTO user=new UserDTO();Set<String> seen=new HashSet<>();
        try {
            for(int i=0;i<reply.size();i+=2){
                if(!(reply.get(i) instanceof String) || !(reply.get(i+1) instanceof String))throw new IllegalArgumentException();
                String field=(String)reply.get(i),value=(String)reply.get(i+1);
                if(!seen.add(field))throw new IllegalArgumentException();
                if("id".equals(field))user.setId(Long.valueOf(value));
                else if("nickName".equals(field))user.setNickName(value);
                else if("icon".equals(field))user.setIcon(value);
                else throw new IllegalArgumentException();
            }
            if(user.getId()==null || user.getId()<=0)throw new IllegalArgumentException();
        }catch(RuntimeException e){throw new ProtectionUnavailableException("TOKEN_HASH_INVALID");}
        return user;
    }
    /**
     * 删除当前凭证。重复、缺失、无效格式幂等成功，系统故障不能报告成功。
     * @param token 当前请求的原始 Token
     * @throws ProtectionUnavailableException 有效格式 Token 的 Redis 删除失败
     */
    public void logout(String token) {
        try {
            if(valid(token)) {
                Boolean result=redis.delete(keys.key(LOGIN_USER_KEY,token));
                if(result==null)throw new ProtectionUnavailableException("TOKEN_DELETE_STATE");
            }
        }catch(RuntimeException e){throw new ProtectionUnavailableException("TOKEN_DELETE_FAILURE");}
        finally{UserHolder.removeUser();}
    }
    private boolean valid(String token){return token!=null && token.matches("[0-9a-fA-F]{32}");}
    private String ttl(){return String.valueOf(TimeUnit.MINUTES.toMillis(properties.getTokenIdleTtlMinutes()));}
}
