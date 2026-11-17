package com.rbi.security;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当前登录用户（由 JWT 解析得到，不落库、不缓存）
 */
@Data
public class CurrentUser implements Serializable {
    private Long userId;
    private String username;
    private String realName;
    private Long deptId;
    /** 当前会话 ID（JWT jti），用于单设备注销 */
    private String jti;
    private Set<String> roles = new LinkedHashSet<>();

    public boolean hasRole(String role) {
        return role != null && roles.contains(role.toUpperCase());
    }

    public boolean hasAnyRole(Set<String> required) {
        if (required == null || required.isEmpty()) return true;
        for (String r : required) if (hasRole(r)) return true;
        return false;
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }
}
