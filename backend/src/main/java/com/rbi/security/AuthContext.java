package com.rbi.security;

import com.rbi.common.BizException;

/**
 * 请求级登录用户上下文。
 * 由 JwtAuthInterceptor 写入并在请求结束时清理，业务代码通过静态方法读取。
 */
public final class AuthContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 可能为 null（未登录调用，如登录接口自身） */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** 必须已登录，否则 401 */
    public static CurrentUser require() {
        CurrentUser u = HOLDER.get();
        if (u == null) throw new BizException(401, "未登录或登录已过期");
        return u;
    }

    /** 当前用户名，未登录时返回 system（用于调度、审计等系统动作） */
    public static String usernameOrSystem() {
        CurrentUser u = HOLDER.get();
        return u == null ? "system" : u.getUsername();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 系统身份：供定时任务等无登录上下文的内部执行使用（等价 ADMIN，但不落任何用户会话） */
    public static CurrentUser systemUser() {
        CurrentUser u = new CurrentUser();
        u.setUsername("system");
        u.setRoles(new java.util.LinkedHashSet<>(java.util.Arrays.asList("ADMIN")));
        return u;
    }
}
