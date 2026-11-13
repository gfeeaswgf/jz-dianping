package com.hmdp.ratelimit;

import com.hmdp.config.ProtectionProperties;
import com.hmdp.exception.ProtectionInvalidRequestException;
import org.springframework.stereotype.Component;
import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** 规范化客户端字面 IP，仅信任明确列出的直连代理，拒绝用可伪造头直接分配额度。 */
@Component
public class ClientIpResolver {
    private final ProtectionProperties properties;
    /**
     * @param properties 明确信任的代理配置，默认不信任任何转发头
     */
    public ClientIpResolver(ProtectionProperties properties) { this.properties=properties; }
    /**
     * 解析不会触发 DNS 查询的规范化字面 IP。
     * @param request 当前 Servlet 请求
     * @return 客户端 IP；不合法的代理头回退到直连对端
     * @throws ProtectionInvalidRequestException 直连地址无法解析时抛出
     */
    public String resolve(HttpServletRequest request) {
        if(request==null) throw new ProtectionInvalidRequestException();
        String peer=literal(request.getRemoteAddr());
        if(peer==null) throw new ProtectionInvalidRequestException();
        boolean trusted=properties.getTrustedProxyAddresses().stream().map(ClientIpResolver::literal).anyMatch(peer::equals);
        if(trusted) {
            // 多个独立同名头也属于多值，不能只取首值而接受攻击者附加的地址。
            java.util.Enumeration<String> headers=request.getHeaders("X-Real-IP");
            if(headers!=null && headers.hasMoreElements()) {
                String value=headers.nextElement();
                if(!headers.hasMoreElements()) {
                    String forwarded=literal(value);
                    if(forwarded!=null) return forwarded;
                }
            }
        }
        return peer;
    }
    private static String literal(String text) {
        if(text==null || text.length()>45 || !text.equals(text.trim())) return null;
        if(text.matches("[0-9]+(\\.[0-9]+){3}")) {
            String[] parts=text.split("\\."); StringBuilder result=new StringBuilder();
            try { for(String part:parts) { int value=Integer.parseInt(part); if(value>255) return null;
                if(result.length()>0) result.append('.'); result.append(value); } }
            catch(NumberFormatException e) { return null; }
            return result.toString();
        }
        // 先限制为含冒号的数字字面量，绝不把域名交给 InetAddress。
        if(!text.contains(":") || !text.matches("[0-9a-fA-F:.]+")) return null;
        try { return InetAddress.getByName(text).getHostAddress().toLowerCase(java.util.Locale.ROOT); }
        catch(UnknownHostException e) { return null; }
    }
}
