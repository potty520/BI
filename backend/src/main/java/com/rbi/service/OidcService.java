package com.rbi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.entity.Role;
import com.rbi.entity.User;
import com.rbi.entity.UserRole;
import com.rbi.mapper.RoleMapper;
import com.rbi.mapper.UserMapper;
import com.rbi.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC 单点登录（授权码流程 + userinfo）。
 *
 * 说明：这里刻意只使用 code -> access_token -> userinfo 的标准三步，
 * 不自行解析 id_token 签名（签名校验交给 userinfo 的 TLS 通道与 IdP 侧），
 * 因此不需要引入 JOSE 依赖；生产环境请确保 userinfo 走 https。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OidcService {

    private static final long STATE_TTL_MS = 5 * 60 * 1000L;

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final AuthService authService;
    private final ObjectMapper om = new ObjectMapper();

    /** state -> 过期时间（单节点内存；多节点部署需换 Redis） */
    private final Map<String, Long> states = new ConcurrentHashMap<>();

    @Value("${rbi.oidc.enabled:false}")
    private boolean enabled;
    @Value("${rbi.oidc.client-id:}")
    private String clientId;
    @Value("${rbi.oidc.client-secret:}")
    private String clientSecret;
    @Value("${rbi.oidc.authorize-url:}")
    private String authorizeUrl;
    @Value("${rbi.oidc.token-url:}")
    private String tokenUrl;
    @Value("${rbi.oidc.userinfo-url:}")
    private String userinfoUrl;
    @Value("${rbi.oidc.redirect-uri:http://127.0.0.1:18088/api/auth/oidc/callback}")
    private String redirectUri;
    @Value("${rbi.oidc.username-claim:preferred_username}")
    private String usernameClaim;
    @Value("${rbi.oidc.auto-create:false}")
    private boolean autoCreate;
    @Value("${rbi.oidc.default-role:VIEWER}")
    private String defaultRole;

    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("authorizeUrl", enabled ? "/api/auth/oidc/authorize" : null);
        return m;
    }

    /** 生成授权地址（带一次性 state） */
    public Map<String, Object> authorize() {
        requireEnabled();
        String state = java.util.UUID.randomUUID().toString().replace("-", "");
        states.put(state, System.currentTimeMillis() + STATE_TTL_MS);
        String url = authorizeUrl
                + (authorizeUrl.contains("?") ? "&" : "?")
                + "response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("openid profile email")
                + "&state=" + enc(state);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("state", state);
        return m;
    }

    /** 回调：校验 state -> 换 token -> 取 userinfo -> 本地用户映射 -> 签发平台令牌 */
    public Map<String, Object> callback(String code, String state) {
        requireEnabled();
        validateState(state);
        if (code == null || code.trim().isEmpty()) throw new BizException(400, "缺少授权码");

        String accessToken = exchangeCode(code);
        JsonNode info = fetchUserInfo(accessToken);
        String username = pickUsername(info);
        if (username == null || username.isEmpty()) {
            throw new BizException(400, "IdP 未返回可用的用户名（claim: " + usernameClaim + "）");
        }

        User user = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (user == null) {
            if (!autoCreate) {
                throw new BizException(403, "单点登录账号未在平台开通: " + username);
            }
            user = createUser(username, info);
        }
        if (user.getStatus() != null && user.getStatus() != 1) throw new BizException(403, "账号已禁用");
        log.info("[SSO] 用户 {} 通过 OIDC 登录", username);
        return authService.loginByUser(user);
    }

    private void validateState(String state) {
        if (state == null || state.isEmpty()) throw new BizException(400, "缺少 state");
        Long expire = states.remove(state);
        if (expire == null) throw new BizException(400, "state 无效或已使用");
        if (expire < System.currentTimeMillis()) throw new BizException(400, "state 已过期，请重新发起登录");
    }

    private String exchangeCode(String code) {
        try {
            String form = "grant_type=authorization_code"
                    + "&code=" + enc(code)
                    + "&client_id=" + enc(clientId)
                    + "&client_secret=" + enc(clientSecret)
                    + "&redirect_uri=" + enc(redirectUri);
            HttpURLConnection conn = (HttpURLConnection) new URL(tokenUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }
            JsonNode node = om.readTree(read(conn));
            if (node.has("error")) {
                throw new BizException(401, "换取令牌失败: " + node.get("error").asText());
            }
            String at = node.has("access_token") ? node.get("access_token").asText() : null;
            if (at == null) throw new BizException(401, "IdP 未返回 access_token");
            return at;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("换取令牌失败: " + e.getMessage());
        }
    }

    private JsonNode fetchUserInfo(String accessToken) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(userinfoUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            return om.readTree(read(conn));
        } catch (Exception e) {
            throw new BizException("获取用户信息失败: " + e.getMessage());
        }
    }

    private String read(HttpURLConnection conn) throws Exception {
        int status = conn.getResponseCode();
        InputStream in = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) throw new BizException("IdP 返回空响应 (HTTP " + status + ")");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        conn.disconnect();
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            throw new BizException("IdP 返回 HTTP " + status + ": " + text);
        }
        return text;
    }

    private String pickUsername(JsonNode info) {
        for (String key : new String[]{usernameClaim, "preferred_username", "email", "sub"}) {
            if (key != null && info.has(key) && !info.get(key).isNull()) {
                String v = info.get(key).asText();
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** 首次 SSO 登录自动建号，并绑定默认角色 */
    private User createUser(String username, JsonNode info) {
        User u = new User();
        u.setUsername(username);
        u.setRealName(info.has("name") ? info.get("name").asText() : username);
        u.setEmail(info.has("email") ? info.get("email").asText() : null);
        u.setStatus(1);
        u.setMustChangePwd(0);
        u.setTokenVersion(0);
        u.setMfaEnabled(0);
        // SSO 用户不使用本地口令：写入一个不可用的随机串
        u.setPassword(authService.encode(java.util.UUID.randomUUID().toString()));
        userMapper.insert(u);

        Role role = roleMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, defaultRole));
        if (role != null) {
            UserRole ur = new UserRole();
            ur.setUserId(u.getId());
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);
        }
        log.info("[SSO] 自动创建用户 {}（默认角色 {}）", username, defaultRole);
        return u;
    }

    private void requireEnabled() {
        if (!enabled) throw new BizException(400, "未启用单点登录（rbi.oidc.enabled=false）");
    }

    private String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
}
