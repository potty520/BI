package com.rbi.service;

import com.rbi.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/**
 * LDAP / AD 目录认证（简单绑定）。
 *
 * 配置示例（rbi.ldap.*）：
 *   enabled=true
 *   url=ldap://dc01.corp.local:389
 *   user-dn-pattern=uid={0},ou=people,dc=corp,dc=local   （AD 可用 {0}@corp.local）
 *   timeout-ms=5000
 *
 * 只做"用用户口令绑定一次"的认证，不读取目录属性；认证成功后由平台侧映射/自动开通本地账号。
 * 注意：LDAP 是明文协议时口令在网络上裸奔，生产必须用 ldaps:// 或 StartTLS。
 */
@Slf4j
@Service
public class LdapAuthService {

    @Value("${rbi.ldap.enabled:false}")
    private boolean enabled;
    @Value("${rbi.ldap.url:}")
    private String url;
    @Value("${rbi.ldap.user-dn-pattern:}")
    private String userDnPattern;
    @Value("${rbi.ldap.timeout-ms:5000}")
    private int timeoutMs;

    public boolean enabled() {
        return enabled;
    }

    /** 用 username/password 绑定目录；成功返回 true，失败返回 false（不抛异常给上层） */
    public boolean authenticate(String username, String password) {
        if (!enabled) return false;
        if (username == null || password == null || password.isEmpty()) return false;
        if (url == null || url.trim().isEmpty() || userDnPattern == null || userDnPattern.trim().isEmpty()) {
            log.warn("[LDAP] 已启用但配置不完整（url / user-dn-pattern）");
            return false;
        }
        String dn = userDnPattern.replace("{0}", username);
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url.trim());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, dn);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(timeoutMs));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(timeoutMs));

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            log.info("[LDAP] 用户 {} 绑定成功", username);
            return true;
        } catch (javax.naming.AuthenticationException e) {
            log.info("[LDAP] 用户 {} 口令错误", username);
            return false;
        } catch (javax.naming.NamingException e) {
            log.warn("[LDAP] 目录不可达或配置错误: {}", e.getMessage());
            throw new BizException(503, "目录服务暂不可用，请使用本地账号登录或联系管理员");
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
