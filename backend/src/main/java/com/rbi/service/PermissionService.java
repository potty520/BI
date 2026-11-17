package com.rbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rbi.common.BizException;
import com.rbi.entity.Permission;
import com.rbi.entity.Role;
import com.rbi.entity.UserRole;
import com.rbi.mapper.PermissionMapper;
import com.rbi.mapper.RoleMapper;
import com.rbi.mapper.UserRoleMapper;
import com.rbi.security.AuthContext;
import com.rbi.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源权限（报表 / 目录的可见与编辑）——把 rbi_permission 表真正用起来。
 *
 * 规则：
 *  1. ADMIN 不受限；
 *  2. 若某资源在 rbi_permission 中没有任何记录，视为"公开"，所有登录用户可见（兼容存量数据）；
 *  3. 只要有记录，就只对授权的角色开放（fail-closed）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    /** 当前用户的角色 ID 列表 */
    public List<Long> roleIdsOf(CurrentUser user) {
        List<Long> ids = new ArrayList<>();
        if (user == null || user.getUserId() == null) return ids;
        LambdaQueryWrapper<UserRole> w = new LambdaQueryWrapper<>();
        w.eq(UserRole::getUserId, user.getUserId());
        for (UserRole ur : userRoleMapper.selectList(w)) ids.add(ur.getRoleId());
        return ids;
    }

    /** 是否可查看指定资源 */
    public boolean canView(String resourceType, Long resourceId) {
        CurrentUser me = AuthContext.get();
        if (me == null) return false;
        if (me.isAdmin()) return true;
        if (resourceId == null) return true;

        List<Long> roleIds = roleIdsOf(me);
        LambdaQueryWrapper<Permission> w = new LambdaQueryWrapper<>();
        w.eq(Permission::getResourceType, resourceType).eq(Permission::getResourceId, resourceId);
        List<Permission> rows = permissionMapper.selectList(w);
        // 未配置权限 = 公开（兼容存量）
        if (rows.isEmpty()) return true;
        if (roleIds.isEmpty()) return false;
        for (Permission p : rows) {
            if (roleIds.contains(p.getRoleId()) && ("view".equalsIgnoreCase(p.getPerm()) || "edit".equalsIgnoreCase(p.getPerm()))) {
                return true;
            }
        }
        return false;
    }

    /** 是否可编辑指定资源（对应 perm=edit） */
    public boolean canEdit(String resourceType, Long resourceId) {
        CurrentUser me = AuthContext.get();
        if (me == null) return false;
        if (me.isAdmin()) return true;
        if (resourceId == null) return true;
        List<Long> roleIds = roleIdsOf(me);
        LambdaQueryWrapper<Permission> w = new LambdaQueryWrapper<>();
        w.eq(Permission::getResourceType, resourceType).eq(Permission::getResourceId, resourceId);
        List<Permission> rows = permissionMapper.selectList(w);
        if (rows.isEmpty()) return true;
        for (Permission p : rows) {
            if (roleIds.contains(p.getRoleId()) && "edit".equalsIgnoreCase(p.getPerm())) return true;
        }
        return false;
    }

    public void assertCanView(String resourceType, Long resourceId) {
        if (!canView(resourceType, resourceId)) {
            throw new BizException(403, "无权查看该资源");
        }
    }

    public List<Permission> list() {
        return permissionMapper.selectList(null);
    }
}
