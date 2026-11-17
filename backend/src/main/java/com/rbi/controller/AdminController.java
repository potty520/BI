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
    private final ReportMapper reportMapper;
    private final DatasetMapper datasetMapper;
    private final DashboardMapper dashboardMapper;
    private final DataScopeMapper dataScopeMapper;
    private final ColumnPolicyMapper columnPolicyMapper;
    private final com.rbi.service.DataScopeService dataScopeService;
    private final com.rbi.export.ExportService exportService;
    private final com.rbi.schedule.ScheduleRunner scheduleRunner;
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
        // 口令只能由服务端生成，禁止客户端直接写入（避免明文/弱口令落库）
        u.setPassword(null);
        if (u.getId() == null) {
            String temp = authService.resetPasswordAfterCreate(u);
            userMapper.insert(u);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", u.getId());
            resp.put("username", u.getUsername());
            resp.put("initialPassword", temp);
            return R.ok(resp);
        }
        User existing = userMapper.selectById(u.getId());
        if (existing == null) throw new com.rbi.common.BizException(404, "用户不存在");
        userMapper.updateById(u);
        return R.ok(u);
    }

    @DeleteMapping("/users/{id}")
    public R<?> delUser(@PathVariable Long id) {
        com.rbi.security.CurrentUser me = com.rbi.security.AuthContext.require();
        if (id.equals(me.getUserId())) throw new com.rbi.common.BizException(400, "不能删除当前登录账号");
        userMapper.deleteById(id);
        return R.ok();
    }

    @PostMapping("/users/{id}/resetpwd")
    public R<?> resetPwd(@PathVariable Long id) {
        com.rbi.security.CurrentUser me = com.rbi.security.AuthContext.require();
        if (id.equals(me.getUserId())) {
            throw new com.rbi.common.BizException(400, "请通过修改个人密码的方式更新自己账号，勿使用重置");
        }
        String temp = authService.resetPassword(id);
        return R.ok("密码已重置，新密码：" + temp + "（请转交用户并提醒及时修改）");
    }

    /** 查看某用户的登录会话（管理员） */
    @GetMapping("/users/{id}/sessions")
    public R<?> userSessions(@PathVariable Long id) {
        return R.ok(authService.listSessions(id));
    }

    /** 强制某用户密码过期：下次登录必须改密 */
    @PostMapping("/users/{id}/expire-password")
    public R<?> expirePassword(@PathVariable Long id) {
        authService.expirePassword(id);
        return R.ok("已将该用户密码置为过期");
    }

    /** 踢下线：令牌版本 +1，该用户所有已登录会话立即失效（不改密码） */
    @PostMapping("/users/{id}/kick")
    public R<?> kickUser(@PathVariable Long id) {
        com.rbi.security.CurrentUser me = com.rbi.security.AuthContext.require();
        if (id.equals(me.getUserId())) throw new com.rbi.common.BizException(400, "请使用退出登录");
        authService.bumpTokenVersion(id);
        return R.ok("已强制该用户下线");
    }

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

    @PostMapping("/depts")
    public R<?> saveDept(@RequestBody com.rbi.entity.Dept d) {
        if (d.getName() == null || d.getName().trim().isEmpty()) {
            throw new com.rbi.common.BizException(400, "部门名称不能为空");
        }
        if (d.getId() == null) deptMapper.insert(d); else deptMapper.updateById(d);
        return R.ok(d);
    }

    @DeleteMapping("/depts/{id}")
    public R<?> delDept(@PathVariable Long id) {
        deptMapper.deleteById(id);
        return R.ok();
    }

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

    // ---- 列级权限 / 脱敏 ----
    @GetMapping("/column-policies")
    public R<?> columnPolicies(@RequestParam(required = false) Long datasetId) {
        LambdaQueryWrapper<ColumnPolicy> w = new LambdaQueryWrapper<>();
        if (datasetId != null) w.eq(ColumnPolicy::getDatasetId, datasetId);
        return R.ok(columnPolicyMapper.selectList(w));
    }

    @PostMapping("/column-policies")
    public R<?> saveColumnPolicy(@RequestBody ColumnPolicy p) {
        if (p.getRoleId() == null || p.getDatasetId() == null) {
            throw new com.rbi.common.BizException(400, "角色与数据集不能为空");
        }
        if (p.getColumnName() == null || !p.getColumnName().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new com.rbi.common.BizException(400, "列名不合法");
        }
        List<String> allowed = java.util.Arrays.asList(
                "hidden", "mask_all", "mask_phone", "mask_idcard", "mask_email", "mask_name");
        String policy = p.getPolicy() == null ? "" : p.getPolicy().toLowerCase();
        if (!allowed.contains(policy)) {
            throw new com.rbi.common.BizException(400, "不支持的脱敏策略: " + p.getPolicy());
        }
        p.setPolicy(policy);
        if (p.getId() == null) columnPolicyMapper.insert(p);
        else columnPolicyMapper.updateById(p);
        return R.ok(p);
    }

    @DeleteMapping("/column-policies/{id}")
    public R<?> delColumnPolicy(@PathVariable Long id) {
        columnPolicyMapper.deleteById(id);
        return R.ok();
    }

    // ---- 行级数据权限 ----
    @GetMapping("/data-scopes")
    public R<?> dataScopes(@RequestParam(required = false) Long datasetId) {
        LambdaQueryWrapper<DataScope> w = new LambdaQueryWrapper<>();
        if (datasetId != null) w.eq(DataScope::getDatasetId, datasetId);
        return R.ok(dataScopeMapper.selectList(w));
    }

    @PostMapping("/data-scopes")
    public R<?> saveDataScope(@RequestBody DataScope s) {
        if (s.getRoleId() == null || s.getDatasetId() == null) {
            throw new com.rbi.common.BizException(400, "角色与数据集不能为空");
        }
        if ("expr".equalsIgnoreCase(s.getScopeType())) {
            // 表达式模式：保存时就校验语法，避免把错误配置留到查询时才暴露
            dataScopeService.validateExpression(s.getFilterExpr());
            if (s.getColumnName() == null || s.getColumnName().trim().isEmpty()) s.setColumnName("expr");
        } else {
            s.setScopeType("values");
            if (s.getColumnName() == null || !s.getColumnName().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new com.rbi.common.BizException(400, "列名不合法");
            }
        }
        if (s.getId() == null) dataScopeMapper.insert(s);
        else dataScopeMapper.updateById(s);
        return R.ok(s);
    }

    @DeleteMapping("/data-scopes/{id}")
    public R<?> delDataScope(@PathVariable Long id) {
        dataScopeMapper.deleteById(id);
        return R.ok();
    }

    // ---- 调度 ----
    @GetMapping("/schedules")
    public R<?> schedules() { return R.ok(scheduleMapper.selectList(null)); }
    @PostMapping("/schedules")
    public R<?> saveSchedule(@RequestBody Schedule s) { scheduleMapper.save(s); return R.ok(s); }
    @DeleteMapping("/schedules/{id}")
    public R<?> delSchedule(@PathVariable Long id) { scheduleMapper.deleteById(id); return R.ok(); }

    /** 立即执行一次调度任务（不影响 cron 的下次执行时间） */
    @PostMapping("/schedules/{id}/run")
    public R<?> runScheduleNow(@PathVariable Long id) {
        scheduleRunner.runNow(id);
        return R.ok("已执行");
    }

    // ---- 日志 ----
    @GetMapping("/logs")
    public R<?> logs(@RequestParam(required = false) String username,
                     @RequestParam(required = false) String module) {
        LambdaQueryWrapper<OpLog> w = new LambdaQueryWrapper<>();
        if (username != null && !username.trim().isEmpty()) w.like(OpLog::getUsername, username.trim());
        if (module != null && !module.trim().isEmpty()) w.eq(OpLog::getModule, module.trim());
        w.orderByDesc(OpLog::getCreateTime).last("LIMIT 500");
        return R.ok(opLogMapper.selectList(w));
    }

    /** 审计日志导出（CSV，带导出人水印） */
    @GetMapping("/logs/export")
    public org.springframework.http.ResponseEntity<byte[]> exportLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module) {
        LambdaQueryWrapper<OpLog> w = new LambdaQueryWrapper<>();
        if (username != null && !username.trim().isEmpty()) w.like(OpLog::getUsername, username.trim());
        if (module != null && !module.trim().isEmpty()) w.eq(OpLog::getModule, module.trim());
        w.orderByDesc(OpLog::getCreateTime).last("LIMIT 5000");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OpLog log : opLogMapper.selectList(w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", log.getId());
            m.put("username", log.getUsername());
            m.put("module", log.getModule());
            m.put("operation", log.getOperation());
            m.put("status", log.getStatus());
            m.put("durationMs", log.getDurationMs());
            m.put("detail", log.getDetail());
            m.put("ip", log.getIp());
            m.put("createTime", log.getCreateTime());
            rows.add(m);
        }
        String user = com.rbi.security.AuthContext.usernameOrSystem();
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        byte[] bytes = exportService.datasetToCsv(rows, "导出人 " + user + " · " + time + " · 仅限内部使用");
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
                .filename("op-logs.csv", java.nio.charset.StandardCharsets.UTF_8).build());
        return new org.springframework.http.ResponseEntity<>(bytes, headers,
                org.springframework.http.HttpStatus.OK);
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
        m.put("uptimeSec", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("timestamp", new Date());
        return R.ok(m);
    }
    @GetMapping("/overview")
    public R<?> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", userMapper.selectCount(null));
        m.put("reports", reportMapper.selectCount(null));
        m.put("datasets", datasetMapper.selectCount(null));
        m.put("dashboards", dashboardMapper.selectCount(null));
        m.put("schedules", scheduleMapper.selectCount(null));
        m.put("metrics", metricMapper.selectCount(null));
        m.put("logsToday", todayLogCount());
        return R.ok(m);
    }

    /** 今日操作数（审计概览） */
    private long todayLogCount() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return opLogMapper.selectCount(new LambdaQueryWrapper<OpLog>().ge(OpLog::getCreateTime, c.getTime()));
    }
}
