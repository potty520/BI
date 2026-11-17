package com.rbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rbi.common.BizException;
import com.rbi.entity.Role;
import com.rbi.entity.User;
import com.rbi.entity.UserRole;
import com.rbi.mapper.RoleMapper;
import com.rbi.mapper.UserMapper;
import com.rbi.mapper.UserRoleMapper;
import com.rbi.security.CurrentUser;
import com.rbi.security.Totp;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证与 RBAC 服务 (M09-01)
 *
 * 令牌只携带 uid / username / roles，权限判断一律以服务端解析结果为准，
 * 前端 localStorage 中的任何标记都不参与授权决策。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** HS256 要求密钥不短于 32 字节 */
    private static final int MIN_SECRET_BYTES = 32;
    /** 临时密码字符集：去掉易混淆的 I/O/0/1 */
    private static final char[] PWD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final com.rbi.mapper.ResetTokenMapper resetTokenMapper;
    private final com.rbi.mapper.UserSessionMapper sessionMapper;
    private final LdapAuthService ldapAuthService;
    private final MailService mailService;

    /** 全局密码有效期（天），<=0 表示不过期 */
    @Value("${rbi.security.pwd-expire-days:90}")
    private int pwdExpireDays;

    /** last_seen 写库节流：同一会话 60 秒内不重复更新 */
    private final java.util.Map<String, Long> lastSeenCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${rbi.security.reset-url:http://127.0.0.1:18088/#/reset-password}")
    private String resetUrl;

    @Value("${rbi.security.reset-debug:false}")
    private boolean resetDebug;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:28800000}")
    private long expiration;

    @Value("${jwt.issuer:rbi-platform}")
    private String issuer;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret 长度不足：HS256 至少需要 " + MIN_SECRET_BYTES + " 字节，请通过环境变量 RBI_JWT_SECRET 配置");
        }
        if (secret.contains("report-bi-platform-secret-key")) {
            log.warn("[安全] 正在使用仓库内置的默认 jwt.secret，生产环境请务必通过 RBI_JWT_SECRET 覆盖");
        }
    }

    /** 登录：校验口令 -> 签发令牌 -> 返回用户信息与角色 */
    public Map<String, Object> login(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new BizException(400, "用户名和密码不能为空");
        }
        String name = username.trim();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, name));
        boolean localOk = user != null && encoder.matches(password, user.getPassword());
        if (!localOk) {
            // 本地口令失败时，若开启了 LDAP，则回退到目录认证（AD/OpenLDAP 简单绑定）
            boolean ldapOk = ldapAuthService != null && ldapAuthService.enabled()
                    && ldapAuthService.authenticate(name, password);
            if (!ldapOk) {
                // 用户不存在与口令错误返回同一提示，避免用户名枚举
                throw new BizException(401, "用户名或密码错误");
            }
            if (user == null) {
                throw new BizException(403, "目录账号未在平台开通: " + name);
            }
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BizException(403, "账号已禁用");
        }

        // 密码过期：允许登录但强制改密
        if (isPwdExpired(user)) {
            User upd = new User();
            upd.setId(user.getId());
            upd.setMustChangePwd(1);
            userMapper.updateById(upd);
            user.setMustChangePwd(1);
            log.info("[安全] 用户 {} 的密码已过期，要求其改密", user.getUsername());
        }

        List<String> roles = rolesOf(user.getId());
        boolean mfaEnabled = user.getMfaEnabled() != null && user.getMfaEnabled() == 1;
        if (mfaEnabled) {
            Map<String, Object> mfaResp = new LinkedHashMap<>();
            mfaResp.put("mfaRequired", true);
            mfaResp.put("mfaToken", issueMfaToken(user));
            return mfaResp;
        }
        return buildLoginResponse(user, roles);
    }

    /** 外部身份（SSO/LDAP）已认证后的登录：复用本地角色与会话机制 */
    public Map<String, Object> loginByUser(User user) {
        return buildLoginResponse(user, rolesOf(user.getId()));
    }

    /** 组装登录成功响应（含令牌与用户信息） */
    private Map<String, Object> buildLoginResponse(User user, List<String> roles) {
        String jti = java.util.UUID.randomUUID().toString().replace("-", "");
        createSession(user, jti);
        String token = issue(user, roles, jti);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("userId", user.getId());
        resp.put("username", user.getUsername());
        resp.put("realName", user.getRealName());
        resp.put("roles", roles);
        resp.put("deptId", user.getDeptId());
        resp.put("mustChangePwd", user.getMustChangePwd() != null && user.getMustChangePwd() == 1);
        resp.put("mfaEnabled", user.getMfaEnabled() != null && user.getMfaEnabled() == 1);
        resp.put("pwdExpired", isPwdExpired(user));
        resp.put("sessionId", jti);
        resp.put("expiresIn", expiration / 1000);
        return resp;
    }

    /** 登录成功后登记会话（IP / UA 便于用户在会话列表里识别设备） */
    private void createSession(User user, String jti) {
        try {
            com.rbi.entity.UserSession s = new com.rbi.entity.UserSession();
            s.setJti(jti);
            s.setUserId(user.getId());
            s.setUsername(user.getUsername());
            s.setIp(clientIp());
            s.setUserAgent(clientUa());
            s.setCreatedAt(new Date());
            s.setLastSeen(new Date());
            s.setExpireAt(new Date(System.currentTimeMillis() + expiration));
            s.setRevoked(0);
            sessionMapper.insert(s);
        } catch (Exception e) {
            log.warn("[会话] 登记失败: {}", e.getMessage());
        }
    }

    private String clientIp() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attrs =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            javax.servlet.http.HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            return xff == null ? req.getRemoteAddr() : xff.split(",")[0].trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String clientUa() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attrs =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            String ua = attrs.getRequest().getHeader("User-Agent");
            return ua == null ? null : (ua.length() > 250 ? ua.substring(0, 250) : ua);
        } catch (Exception e) {
            return null;
        }
    }

    /** 密码是否已过期 */
    public boolean isPwdExpired(User user) {
        int days = user.getPwdExpireDays() != null && user.getPwdExpireDays() > 0
                ? user.getPwdExpireDays() : pwdExpireDays;
        if (days <= 0) return false;
        Date updated = user.getPwdUpdatedAt();
        if (updated == null) return false;
        long ageMs = System.currentTimeMillis() - updated.getTime();
        return ageMs > days * 24L * 60 * 60 * 1000;
    }

    /** 二次验证：用 mfaToken + 6 位动态码换取正式令牌 */
    public Map<String, Object> verifyMfaLogin(String mfaToken, String code) {
        Long uid;
        try {
            Claims c = Jwts.parserBuilder().setSigningKey(key()).requireIssuer(issuer).build()
                    .parseClaimsJws(mfaToken).getBody();
            if (c.get("mfa") == null) throw new BizException(401, "二次验证令牌无效");
            uid = Long.valueOf(String.valueOf(c.get("uid")));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(401, "二次验证已过期，请重新登录");
        }
        User user = userMapper.selectById(uid);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(401, "账号不可用");
        }
        if (!Totp.verify(user.getMfaSecret(), code, System.currentTimeMillis())) {
            throw new BizException(401, "验证码不正确");
        }
        return buildLoginResponse(user, rolesOf(user.getId()));
    }

    /** 生成 MFA 密钥（暂未启用，需用动态码确认） */
    public Map<String, Object> setupMfa(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new BizException(404, "用户不存在");
        String secret = Totp.generateSecret();
        User upd = new User();
        upd.setId(userId);
        upd.setMfaSecret(secret);
        upd.setMfaEnabled(0);
        userMapper.updateById(upd);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("secret", secret);
        resp.put("otpauth", Totp.otpauthUrl("BI-Platform", u.getUsername(), secret));
        return resp;
    }

    /** 启用 MFA：校验一次动态码 */
    public void enableMfa(Long userId, String code) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new BizException(404, "用户不存在");
        if (u.getMfaSecret() == null) throw new BizException(400, "请先调用 setup 获取密钥");
        if (!Totp.verify(u.getMfaSecret(), code, System.currentTimeMillis())) {
            throw new BizException(400, "验证码不正确");
        }
        User upd = new User();
        upd.setId(userId);
        upd.setMfaEnabled(1);
        userMapper.updateById(upd);
        log.info("[MFA] 用户 {} 已启用二次验证", u.getUsername());
    }

    /** 关闭 MFA：同样需要动态码，避免令牌被盗后直接关闭 */
    public void disableMfa(Long userId, String code) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new BizException(404, "用户不存在");
        if (u.getMfaEnabled() == null || u.getMfaEnabled() != 1) return;
        if (!Totp.verify(u.getMfaSecret(), code, System.currentTimeMillis())) {
            throw new BizException(400, "验证码不正确");
        }
        User upd = new User();
        upd.setId(userId);
        upd.setMfaEnabled(0);
        upd.setMfaSecret(null);
        userMapper.updateById(upd);
        log.info("[MFA] 用户 {} 已关闭二次验证", u.getUsername());
    }

    /** last_seen 节流更新（60 秒一次），避免每个请求都写库 */
    private void touchSession(String jti) {
        long now = System.currentTimeMillis();
        Long last = lastSeenCache.get(jti);
        if (last != null && now - last < 60_000L) return;
        lastSeenCache.put(jti, now);
        try {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.rbi.entity.UserSession> w =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
            w.eq("jti", jti).set("last_seen", new Date());
            sessionMapper.update(null, w);
        } catch (Exception ignored) {
        }
    }

    /** 我的会话列表（未注销且未过期） */
    public List<com.rbi.entity.UserSession> listSessions(Long userId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.rbi.entity.UserSession> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        w.eq(com.rbi.entity.UserSession::getUserId, userId)
                .eq(com.rbi.entity.UserSession::getRevoked, 0)
                .orderByDesc(com.rbi.entity.UserSession::getLastSeen);
        List<com.rbi.entity.UserSession> out = new ArrayList<>();
        for (com.rbi.entity.UserSession s : sessionMapper.selectList(w)) {
            if (s.getExpireAt() == null || s.getExpireAt().after(new Date())) out.add(s);
        }
        return out;
    }

    /** 注销指定会话（只能注销自己的；管理员走 kick） */
    public void revokeSession(String jti, Long userId) {
        if (jti == null || jti.isEmpty()) throw new BizException(400, "会话 ID 不能为空");
        com.rbi.entity.UserSession s = sessionMapper.selectById(jti);
        if (s == null) throw new BizException(404, "会话不存在");
        if (userId == null || !userId.equals(s.getUserId())) throw new BizException(403, "无权注销该会话");
        s.setRevoked(1);
        sessionMapper.updateById(s);
        lastSeenCache.remove(jti);
        log.info("[会话] 用户 {} 注销了会话 {}", s.getUsername(), jti);
    }

    /** 注销当前会话 */
    public void logout(String jti) {
        if (jti == null || jti.isEmpty()) return;
        com.rbi.entity.UserSession s = sessionMapper.selectById(jti);
        if (s != null) {
            s.setRevoked(1);
            sessionMapper.updateById(s);
        }
        lastSeenCache.remove(jti);
    }

    /** 强制指定用户密码过期（下次登录必须改密） */
    public void expirePassword(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new BizException(404, "用户不存在");
        int days = u.getPwdExpireDays() != null && u.getPwdExpireDays() > 0 ? u.getPwdExpireDays() : pwdExpireDays;
        if (days <= 0) days = 90;
        User upd = new User();
        upd.setId(userId);
        upd.setPwdUpdatedAt(new Date(System.currentTimeMillis() - (days + 1) * 24L * 60 * 60 * 1000));
        upd.setMustChangePwd(1);
        userMapper.updateById(upd);
        log.info("[安全] 用户 {} 的密码已被管理员置为过期", u.getUsername());
    }

    /** 修改本人密码：校验原密码 + 强度，成功后清除强制改密标记 */
    public void changePassword(Long userId, String oldPwd, String newPwd) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new BizException(404, "用户不存在");
        if (oldPwd == null || oldPwd.isEmpty() || !encoder.matches(oldPwd, u.getPassword())) {
            throw new BizException(400, "原密码不正确");
        }
        if (newPwd != null && encoder.matches(newPwd, u.getPassword())) {
            throw new BizException(400, "新密码不能与原密码相同");
        }
        validateStrength(newPwd, u.getUsername());
        User upd = new User();
        upd.setId(userId);
        upd.setPassword(encoder.encode(newPwd));
        upd.setMustChangePwd(0);
        upd.setPwdUpdatedAt(new Date());
        userMapper.updateById(upd);
        // 改密后旧令牌全部失效
        bumpTokenVersion(userId);
        log.info("[安全] 用户 {} 修改了自己的密码", u.getUsername());
    }

    /**
     * 申请找回密码。
     * 无论账号是否存在、是否有邮箱、邮件是否发送成功，对外表现一致（避免账号枚举）。
     * 返回 token 仅在 rbi.security.reset-debug=true 时用于联调。
     */
    public String requestReset(String username, String ip) {
        if (username == null || username.trim().isEmpty()) return null;
        try {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username.trim()));
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                log.info("[找回密码] 账号不存在或已禁用: {}", username);
                return null;
            }
            String token = java.util.UUID.randomUUID().toString().replace("-", "");
            com.rbi.entity.ResetToken rt = new com.rbi.entity.ResetToken();
            rt.setToken(token);
            rt.setUserId(user.getId());
            rt.setExpireAt(new Date(System.currentTimeMillis() + 30 * 60 * 1000L));
            rt.setUsed(0);
            rt.setCreateTime(new Date());
            resetTokenMapper.insert(rt);

            String link = resetUrl + (resetUrl.contains("?") ? "&" : "?") + "token=" + token;
            if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
                try {
                    mailService.sendResetLink(user.getEmail().trim(), link, user.getUsername());
                } catch (Exception e) {
                    log.warn("[找回密码] 邮件发送失败: {}", e.getMessage());
                }
            } else {
                log.warn("[找回密码] 用户 {} 未配置邮箱，无法发送重置链接", user.getUsername());
            }
            log.info("[找回密码] 已为用户 {} 生成重置令牌（来源 IP {}）", user.getUsername(), ip);
            return resetDebug ? token : null;
        } catch (Exception e) {
            log.error("[找回密码] 处理失败", e);
            return null;
        }
    }

    /** 用令牌重置密码 */
    public void resetByToken(String token, String newPwd) {
        if (token == null || token.trim().isEmpty()) throw new BizException(400, "重置令牌不能为空");
        com.rbi.entity.ResetToken rt = resetTokenMapper.selectById(token.trim());
        if (rt == null) throw new BizException(400, "重置链接无效");
        if (rt.getUsed() != null && rt.getUsed() == 1) throw new BizException(400, "重置链接已使用，请重新申请");
        if (rt.getExpireAt() == null || rt.getExpireAt().before(new Date())) {
            throw new BizException(400, "重置链接已过期，请重新申请");
        }
        User user = userMapper.selectById(rt.getUserId());
        if (user == null) throw new BizException(400, "用户不存在");
        validateStrength(newPwd, user.getUsername());

        User upd = new User();
        upd.setId(user.getId());
        upd.setPassword(encoder.encode(newPwd));
        upd.setMustChangePwd(0);
        userMapper.updateById(upd);

        rt.setUsed(1);
        resetTokenMapper.updateById(rt);
        bumpTokenVersion(user.getId());
        log.info("[找回密码] 用户 {} 已通过令牌重置密码", user.getUsername());
    }

    /** 修改本人资料（目前仅姓名） */    public void updateProfile(Long userId, String realName) {
        if (realName == null || realName.trim().isEmpty()) throw new BizException(400, "姓名不能为空");
        String name = realName.trim();
        if (name.length() > 64) throw new BizException(400, "姓名不能超过 64 个字符");
        User upd = new User();
        upd.setId(userId);
        upd.setRealName(name);
        userMapper.updateById(upd);
    }

    private void validateStrength(String pwd, String username) {
        if (pwd == null || pwd.length() < 8) throw new BizException(400, "密码至少 8 位");
        if (pwd.length() > 64) throw new BizException(400, "密码不能超过 64 位");
        if (!pwd.matches(".*[A-Za-z].*") || !pwd.matches(".*\\d.*")) {
            throw new BizException(400, "密码需同时包含字母和数字");
        }
        if (username != null && pwd.equalsIgnoreCase(username)) {
            throw new BizException(400, "密码不能与用户名相同");
        }
    }

    /** 解析令牌，失败一律 401 */
    public CurrentUser parse(String token) {
        try {
            Claims c = Jwts.parserBuilder()
                    .setSigningKey(key())
                    .requireIssuer(issuer)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            if (c.get("mfa") != null) throw new BizException(401, "需要完成二次验证");
            CurrentUser u = new CurrentUser();
            u.setUsername(c.getSubject());
            Object uid = c.get("uid");
            if (uid != null) u.setUserId(Long.valueOf(String.valueOf(uid)));
            Set<String> roles = new LinkedHashSet<>();
            Object raw = c.get("roles");
            if (raw instanceof Collection) {
                for (Object o : (Collection<?>) raw) roles.add(String.valueOf(o).toUpperCase());
            }
            u.setRoles(roles);
            Object dept = c.get("dept");
            if (dept != null) u.setDeptId(Long.valueOf(String.valueOf(dept)));

            // 令牌版本校验：改密、重置、踢下线后旧令牌立即失效
            User dbUser = u.getUserId() == null ? null : userMapper.selectById(u.getUserId());
            if (dbUser == null) throw new BizException(401, "账号不存在或已被删除");
            int ver = c.get("ver") == null ? 0 : Integer.parseInt(String.valueOf(c.get("ver")));
            int dbVer = dbUser.getTokenVersion() == null ? 0 : dbUser.getTokenVersion();
            if (ver != dbVer) throw new BizException(401, "登录状态已失效，请重新登录");
            if (dbUser.getStatus() != null && dbUser.getStatus() != 1) throw new BizException(403, "账号已禁用");
            u.setDeptId(dbUser.getDeptId());
            u.setRealName(dbUser.getRealName());

            // 会话校验：支持单设备注销；无 jti 的旧令牌一律拒绝
            String jti = c.getId();
            if (jti == null || jti.isEmpty()) throw new BizException(401, "登录凭证无效");
            com.rbi.entity.UserSession session = sessionMapper.selectById(jti);
            if (session == null) throw new BizException(401, "会话不存在，请重新登录");
            if (session.getRevoked() != null && session.getRevoked() == 1) {
                throw new BizException(401, "该设备会话已被注销，请重新登录");
            }
            if (session.getExpireAt() != null && session.getExpireAt().before(new Date())) {
                throw new BizException(401, "会话已过期，请重新登录");
            }
            u.setJti(jti);
            touchSession(jti);
            return u;
        } catch (ExpiredJwtException e) {
            throw new BizException(401, "登录已过期，请重新登录");
        } catch (BizException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(401, "登录凭证无效");
        }
    }

    /** 使该用户所有已签发令牌失效（+1 版本号） */
    public void bumpTokenVersion(Long userId) {
        if (userId == null) return;
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<User> w =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        w.eq("id", userId).setSql("token_version = COALESCE(token_version, 0) + 1");
        userMapper.update(null, w);
    }

    /** 兼容旧调用：仅取用户名 */
    public String usernameFromToken(String token) {
        return parse(token).getUsername();
    }

    /** 登录用户资料（前端展示用，不含口令） */
    public Map<String, Object> profile(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (userId == null) return m;
        User u = userMapper.selectById(userId);
        if (u != null) {
            m.put("userId", u.getId());
            m.put("username", u.getUsername());
            m.put("realName", u.getRealName());
            m.put("deptId", u.getDeptId());
            m.put("mustChangePwd", u.getMustChangePwd() != null && u.getMustChangePwd() == 1);
            m.put("mfaEnabled", u.getMfaEnabled() != null && u.getMfaEnabled() == 1);
            m.put("email", u.getEmail());
        }
        return m;
    }

    /** 新建用户时生成初始口令并写入实体（明文只返回给调用方一次） */
    public String resetPasswordAfterCreate(User u) {
        String temp = randomPassword(10);
        u.setPassword(encoder.encode(temp));
        u.setMustChangePwd(1);
        return temp;
    }

    /** 重置密码为随机临时口令，返回明文由管理员转交用户 */
    public String resetPassword(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new BizException(404, "用户不存在");
        String temp = randomPassword(10);
        User upd = new User();
        upd.setId(userId);
        upd.setPassword(encoder.encode(temp));
        upd.setMustChangePwd(1);
        userMapper.updateById(upd);
        bumpTokenVersion(userId);
        log.info("[安全] 用户 {} 的密码已由 {} 重置", u.getUsername(), com.rbi.security.AuthContext.usernameOrSystem());
        return temp;
    }

    public List<String> rolesOf(Long userId) {
        List<String> roles = new ArrayList<>();
        LambdaQueryWrapper<UserRole> w = new LambdaQueryWrapper<>();
        w.eq(UserRole::getUserId, userId);
        for (UserRole link : userRoleMapper.selectList(w)) {
            Role role = roleMapper.selectById(link.getRoleId());
            if (role != null && role.getRoleCode() != null) roles.add(role.getRoleCode().toUpperCase());
        }
        return roles;
    }

    public String encode(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String encoded) {
        return encoded != null && encoder.matches(raw, encoded);
    }

    private String issue(User user, List<String> roles, String jti) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(user.getUsername())
                .setIssuer(issuer)
                .setId(jti)
                .claim("uid", user.getId())
                .claim("roles", roles)
                .claim("ver", user.getTokenVersion() == null ? 0 : user.getTokenVersion())
                .claim("dept", user.getDeptId())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expiration))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** 二次验证待认证令牌（仅可用于换取正式令牌，5 分钟有效） */
    private String issueMfaToken(User user) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(user.getUsername())
                .setIssuer(issuer)
                .claim("uid", user.getId())
                .claim("mfa", 1)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + 5 * 60 * 1000L))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private String randomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(PWD_CHARS[random.nextInt(PWD_CHARS.length)]);
        return sb.toString();
    }
}
