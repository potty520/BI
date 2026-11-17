package com.rbi.security;

import com.rbi.common.BizException;
import com.rbi.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 统一鉴权拦截器：校验 JWT -> 写入 AuthContext -> 校验角色。
 * 注册路径见 WebConfig：/api/**，其中登录接口放行。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 跨域预检不校验
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;

        String token = resolveToken(request);
        if (token == null || token.isEmpty()) throw new BizException(401, "未登录或登录已过期");

        CurrentUser user = authService.parse(token);
        AuthContext.set(user);

        // 1) 管理接口统一要求 ADMIN
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/admin") && !user.isAdmin()) {
            throw new BizException(403, "需要管理员权限");
        }

        // 2) 方法/类上的 @RequireRole
        Set<String> required = requiredRoles(handler);
        if (!required.isEmpty() && !user.hasAnyRole(required)) {
            throw new BizException(403, "无权限执行该操作");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    private Set<String> requiredRoles(Object handler) {
        Set<String> roles = new LinkedHashSet<>();
        if (handler instanceof HandlerMethod) {
            HandlerMethod hm = (HandlerMethod) handler;
            RequireRole ann = hm.getMethodAnnotation(RequireRole.class);
            if (ann == null) ann = hm.getBeanType().getAnnotation(RequireRole.class);
            if (ann != null) roles.addAll(Arrays.asList(ann.value()));
        }
        return roles;
    }

    /** 支持 Authorization: Bearer xxx / Authorization: xxx / 自定义 X-Token */
    private String resolveToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.trim().isEmpty()) {
            return auth.replaceFirst("(?i)^Bearer\\s+", "").trim();
        }
        String alt = request.getHeader("X-Token");
        return alt == null ? null : alt.trim();
    }
}
