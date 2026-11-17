package com.rbi.controller;

import com.rbi.common.R;
import com.rbi.security.AuthContext;
import com.rbi.security.CurrentUser;
import com.rbi.security.LoginAttemptGuard;
import com.rbi.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final LoginAttemptGuard guard;
    private final com.rbi.service.OidcService oidcService;

    /**
     * 登录。失败次数超阈值会临时锁定（防暴力破解）。
     * 登录接口在 WebConfig 中放行，无需令牌。
     */
    @PostMapping("/login")
    public R<?> login(@RequestBody Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        guard.checkNotLocked(username);
        try {
            Map<String, Object> resp = auth.login(username, password);
            guard.recordSuccess(username);
            return R.ok(resp);
        } catch (RuntimeException e) {
            guard.recordFailure(username);
            throw e;
        }
    }

    /** 校验当前令牌并返回登录用户（前端启动时调用） */
    @GetMapping("/me")
    public R<?> me() {
        CurrentUser u = AuthContext.require();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.putAll(auth.profile(u.getUserId()));
        m.put("username", u.getUsername());
        m.put("roles", u.getRoles());
        m.put("userId", u.getUserId());
        return R.ok(m);
    }

    /** 兼容旧调用 */
    @GetMapping("/verify")
    public R<?> verify() {
        return R.ok(AuthContext.require().getUsername());
    }

    /** 申请找回密码（无需登录，避免账号枚举：无论邮箱是否存在都返回同一提示） */
    @PostMapping("/forgot")
    public R<?> forgot(@RequestBody java.util.Map<String, String> body,
                       javax.servlet.http.HttpServletRequest request) {
        String token = auth.requestReset(body == null ? null : body.get("username"), request.getRemoteAddr());
        return R.ok(token == null ? "如果账号存在，重置邮件已发送，请查收邮箱" : token);
    }

    /** 用令牌重置密码（无需登录） */
    @PostMapping("/reset")
    public R<?> reset(@RequestBody java.util.Map<String, String> body) {
        auth.resetByToken(body == null ? null : body.get("token"),
                body == null ? null : body.get("newPassword"));
        return R.ok("密码已重置，请使用新密码登录");
    }

    /** 单点登录：是否启用 */
    @GetMapping("/oidc/config")
    public R<?> oidcConfig() {
        return R.ok(oidcService.config());
    }

    /** 单点登录：获取授权地址（前端跳转） */
    @GetMapping("/oidc/authorize")
    public R<?> oidcAuthorize() {
        return R.ok(oidcService.authorize());
    }

    /** 单点登录：IdP 回调，换取平台令牌 */
    @GetMapping("/oidc/callback")
    public R<?> oidcCallback(@org.springframework.web.bind.annotation.RequestParam(required = false) String code,
                             @org.springframework.web.bind.annotation.RequestParam(required = false) String state) {
        return R.ok(oidcService.callback(code, state));
    }

    /** 二次验证：用 mfaToken + 6 位动态码换取正式令牌（无需登录） */
    @PostMapping("/login/mfa")
    public R<?> loginMfa(@RequestBody java.util.Map<String, String> body) {
        return R.ok(auth.verifyMfaLogin(body == null ? null : body.get("mfaToken"),
                body == null ? null : body.get("code")));
    }

    /** 退出当前设备（只注销本会话） */
    @PostMapping("/logout")
    public R<?> logout() {
        auth.logout(AuthContext.require().getJti());
        return R.ok("已退出登录");
    }

    /** 退出所有设备（令牌版本 +1，全部会话失效） */
    @PostMapping("/logout-all")
    public R<?> logoutAll() {
        auth.bumpTokenVersion(AuthContext.require().getUserId());
        return R.ok("所有会话已失效");
    }

    /** 我的登录会话列表 */
    @GetMapping("/sessions")
    public R<?> sessions() {
        CurrentUser me = AuthContext.require();
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.rbi.entity.UserSession s : auth.listSessions(me.getUserId())) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("jti", s.getJti());
            m.put("ip", s.getIp());
            m.put("userAgent", s.getUserAgent());
            m.put("createdAt", s.getCreatedAt());
            m.put("lastSeen", s.getLastSeen());
            m.put("current", s.getJti().equals(me.getJti()));
            out.add(m);
        }
        return R.ok(out);
    }

    /** 注销指定会话（单设备踢出） */
    @DeleteMapping("/sessions/{jti}")
    public R<?> revokeSession(@PathVariable String jti) {
        CurrentUser me = AuthContext.require();
        auth.revokeSession(jti, me.getUserId());
        return R.ok("该会话已注销");
    }

    /** 生成 MFA 密钥（返回 secret 与 otpauth 链接，尚未启用） */
    @PostMapping("/mfa/setup")
    public R<?> mfaSetup() {
        return R.ok(auth.setupMfa(AuthContext.require().getUserId()));
    }

    /** 启用 MFA（需提交一次动态码） */
    @PostMapping("/mfa/enable")
    public R<?> mfaEnable(@RequestBody java.util.Map<String, String> body) {
        auth.enableMfa(AuthContext.require().getUserId(), body == null ? null : body.get("code"));
        return R.ok("已启用二次验证");
    }

    /** 关闭 MFA（需提交动态码，防令牌被盗后直接关闭） */
    @PostMapping("/mfa/disable")
    public R<?> mfaDisable(@RequestBody java.util.Map<String, String> body) {
        auth.disableMfa(AuthContext.require().getUserId(), body == null ? null : body.get("code"));
        return R.ok("已关闭二次验证");
    }

    /** 修改本人密码（校验原密码 + 强度） */
    @PostMapping("/password")
    public R<?> changePassword(@RequestBody java.util.Map<String, String> body) {
        CurrentUser me = AuthContext.require();
        auth.changePassword(me.getUserId(),
                body == null ? null : body.get("oldPassword"),
                body == null ? null : body.get("newPassword"));
        return R.ok("密码已修改，请使用新密码重新登录");
    }

    /** 修改本人资料 */
    @PutMapping("/profile")
    public R<?> updateProfile(@RequestBody java.util.Map<String, String> body) {
        CurrentUser me = AuthContext.require();
        auth.updateProfile(me.getUserId(), body == null ? null : body.get("realName"));
        return R.ok("资料已保存");
    }
}
