package com.rbi.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rbi.common.R;
import com.rbi.entity.*;
import com.rbi.mapper.*;
import com.rbi.service.AuthService;
import com.rbi.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 平台管理 (M09) + 运维监控 (M10)
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final DeptMapper deptMapper;
    private final DirectoryMapper directoryMapper;
    private final PermissionMapper permissionMapper;
    private final ScheduleMapper scheduleMapper;
    private final OpLogMapper opLogMapper;
    private final MetricMapper metricMapper;
    private final AnalysisMapper analysisMapper;
    private final AuthService authService;

    // ---- 用户 ----
    @GetMapping("/users")
    public R<?> users() {
        List<User> users = userMapper.selectList(null);
        for (User u : users) u.setPassword("******");
        return R.ok(users);
    }
    @PostMapping("/users")
    public R<?> saveUser(@RequestBody User u) {
        if (u.getId()==null) { u.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("123456")); userMapper.insert(u); }
        else userMapper.updateById(u);
        return R.ok(u);
    }
    @DeleteMapping("/users/{id}")
    public R<?> delUser(@PathVariable Long id) { userMapper.deleteById(id); return R.ok(); }
    @PostMapping("/users/{id}/resetpwd")
    public R<?> resetPwd(@PathVariable Long id) { authService.resetPassword(id); return R.ok("密码已重置为 123456"); }

    // ---- 角色 ----
    @GetMapping("/roles")
    public R<?> roles() { return R.ok(roleMapper.selectList(null)); }
    @PostMapping("/roles")
    public R<?> saveRole(@RequestBody Role r) { roleMapper.save(r); return R.ok(r); }
    @DeleteMapping("/roles/{id}")
    public R<?> delRole(@PathVariable Long id) { roleMapper.deleteById(id); return R.ok(); }
    @GetMapping("/users/{id}/roles")
    public R<?> userRoles(@PathVariable Long id) {
        LambdaQueryWrapper<UserRole> w = new LambdaQueryWrapper<>(); w.eq(UserRole::getUserId,id);
        List<Long> roleIds = new ArrayList<>();
        for (UserRole ur : userRoleMapper.selectList(w)) roleIds.add(ur.getRoleId());
        return R.ok(roleIds);
    }
    @PostMapping("/users/{id}/roles")
    public R<?> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        LambdaQueryWrapper<UserRole> w = new LambdaQueryWrapper<>(); w.eq(UserRole::getUserId,id);
        userRoleMapper.delete(w);
        for (Long rid : roleIds) { UserRole ur = new UserRole(); ur.setUserId(id); ur.setRoleId(rid); userRoleMapper.insert(ur); }
        return R.ok();
    }

    // ---- 部门 ----
    @GetMapping("/depts")
    public R<?> depts() { return R.ok(deptMapper.selectList(null)); }

    // ---- 目录 ----
    @GetMapping("/directories")
    public R<?> directories() { return R.ok(directoryMapper.selectList(null)); }
    @PostMapping("/directories")
    public R<?> saveDirectory(@RequestBody Directory d) { directoryMapper.save(d); return R.ok(d); }
    @DeleteMapping("/directories/{id}")
    public R<?> delDirectory(@PathVariable Long id) { directoryMapper.deleteById(id); return R.ok(); }

    // ---- 权限 ----
    @GetMapping("/permissions")
    public R<?> permissions() { return R.ok(permissionMapper.selectList(null)); }
    @PostMapping("/permissions")
    public R<?> savePermission(@RequestBody Permission p) { permissionMapper.save(p); return R.ok(p); }
    @DeleteMapping("/permissions/{id}")
    public R<?> delPermission(@PathVariable Long id) { permissionMapper.deleteById(id); return R.ok(); }

    // ---- 调度 ----
    @GetMapping("/schedules")
    public R<?> schedules() { return R.ok(scheduleMapper.selectList(null)); }
    @PostMapping("/schedules")
    public R<?> saveSchedule(@RequestBody Schedule s) { scheduleMapper.save(s); return R.ok(s); }
    @DeleteMapping("/schedules/{id}")
    public R<?> delSchedule(@PathVariable Long id) { scheduleMapper.deleteById(id); return R.ok(); }

    // ---- 日志 ----
    @GetMapping("/logs")
    public R<?> logs() {
        LambdaQueryWrapper<OpLog> w = new LambdaQueryWrapper<>(); w.orderByDesc(OpLog::getCreateTime);
        return R.ok(opLogMapper.selectList(w));
    }

    // ---- 指标 M08 ----
    @GetMapping("/metrics")
    public R<?> metrics() { return R.ok(metricMapper.selectList(null)); }
    @PostMapping("/metrics")
    public R<?> saveMetric(@RequestBody Metric m) { metricMapper.save(m); return R.ok(m); }
    @DeleteMapping("/metrics/{id}")
    public R<?> delMetric(@PathVariable Long id) { metricMapper.deleteById(id); return R.ok(); }

    // ---- 自助分析配置保存 M07-05 ----
    @GetMapping("/analyses")
    public R<?> analyses() { return R.ok(analysisMapper.selectList(null)); }
    @PostMapping("/analyses")
    public R<?> saveAnalysis(@RequestBody Analysis a) { analysisMapper.save(a); return R.ok(a); }
    @DeleteMapping("/analyses/{id}")
    public R<?> delAnalysis(@PathVariable Long id) { analysisMapper.deleteById(id); return R.ok(); }

    // ---- 运维监控 M10-03 ----
    @GetMapping("/health")
    public R<?> health() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        Runtime rt = Runtime.getRuntime();
        m.put("cpuCores", rt.availableProcessors());
        m.put("maxMemoryMB", rt.maxMemory()/1024/1024);
        m.put("usedMemoryMB", (rt.totalMemory()-rt.freeMemory())/1024/1024);
        m.put("node", "single-node-" + java.net.InetAddress.getLoopbackAddress().getHostAddress());
        m.put("uptimeSec", (System.currentTimeMillis()-startTime)/1000);
        m.put("timestamp", new Date());
        return R.ok(m);
    }
    private static final long startTime = System.currentTimeMillis();

    @GetMapping("/overview")
    public R<?> overview() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("users", userMapper.selectCount(null));
        m.put("reports", 0);
        m.put("datasets", 0);
        m.put("dashboards", 0);
        m.put("schedules", scheduleMapper.selectCount(null));
        return R.ok(m);
    }
}
