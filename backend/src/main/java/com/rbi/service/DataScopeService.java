package com.rbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rbi.common.BizException;
import com.rbi.datasource.SqlSafeBuilder;
import com.rbi.entity.DataScope;
import com.rbi.mapper.DataScopeMapper;
import com.rbi.security.AuthContext;
import com.rbi.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 行级数据权限（Data Scope）
 *
 * 两种模式（同一张表按 scope_type 区分）：
 *  - values：列值白名单，生成 `col IN ('a','b')`；
 *  - expr  ：受限表达式，如 `amount < 10000 AND region = '华北'`，仅允许白名单语法。
 *
 * 生成的条件由数据集 SQL 的 ${row_filter} 占位符注入；缺少占位符时 fail-closed（403）。
 * 表达式中的 ${deptId} / ${userId} 会替换为当前用户上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataScopeService {

    private static final String IDENT = "[a-zA-Z_][a-zA-Z0-9_]*";

    /** 表达式允许的字符集：标识符、数字、比较符、括号、引号、逗号、百分号、点、空白、负号、中日韩字符 */
    private static final java.util.regex.Pattern ALLOWED =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_\\s<>=!()'.,%\\-\\u4e00-\\u9fff]+$");

    /** 明确禁止的关键字（用词边界匹配，防子查询/DML/DDL） */
    private static final java.util.regex.Pattern BANNED_KEYWORDS =
            java.util.regex.Pattern.compile(
                    "(?i)\\b(select|insert|update|delete|drop|truncate|alter|union|exec|execute|call|create|grant)\\b");

    /** 明确禁止的片段（注释、堆叠语句、延时函数） */
    private static final List<String> BANNED = Arrays.asList(
            ";", "--", "/*", "*/", " sleep(", " benchmark(", " load_file(", " into outfile");

    /** 允许使用的函数（其余 ident( 一律拒绝，避免被当作子查询/危险函数） */
    private static final List<String> ALLOWED_FUNCS = Arrays.asList(
            "in", "lower", "upper", "coalesce", "abs", "round", "year", "month", "day", "length", "trim");

    private final DataScopeMapper dataScopeMapper;
    private final PermissionService permissionService;
    private final com.rbi.mapper.DeptMapper deptMapper;

    /** 生成当前用户在该数据集上的行级过滤片段；null 表示不限制 */
    public String rowFilter(Long datasetId) {
        CurrentUser me = AuthContext.get();
        if (me == null || me.isAdmin()) return null;
        if (datasetId == null || datasetId <= 0) return null;

        List<Long> roleIds = permissionService.roleIdsOf(me);
        if (roleIds.isEmpty()) return null;

        LambdaQueryWrapper<DataScope> w = new LambdaQueryWrapper<>();
        w.eq(DataScope::getDatasetId, datasetId).in(DataScope::getRoleId, roleIds);
        List<DataScope> rows = dataScopeMapper.selectList(w);
        if (rows.isEmpty()) return null;

        Map<String, List<String>> byColumn = new LinkedHashMap<>();
        Map<String, Boolean> unrestricted = new LinkedHashMap<>();
        List<String> exprParts = new ArrayList<>();

        for (DataScope s : rows) {
            if ("expr".equalsIgnoreCase(s.getScopeType())) {
                String expr = substituteContext(s.getFilterExpr(), me);
                validateExpression(expr);
                exprParts.add("(" + expr + ")");
                continue;
            }
            String col = s.getColumnName();
            if (col == null || !col.matches(IDENT)) {
                log.warn("[数据权限] 忽略非法列名: {}", col);
                continue;
            }
            String key = col.toLowerCase(Locale.ROOT);
            byColumn.computeIfAbsent(key, k -> new ArrayList<>());
            unrestricted.putIfAbsent(key, Boolean.FALSE);
            String v = s.getScopeValue();
            if (v == null || v.trim().isEmpty()) {
                unrestricted.put(key, Boolean.TRUE);
            } else {
                for (String part : v.split(",")) {
                    String val = part.trim();
                    if (!val.isEmpty()) byColumn.get(key).add(val);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byColumn.entrySet()) {
            if (Boolean.TRUE.equals(unrestricted.get(e.getKey()))) continue;
            List<String> vals = e.getValue();
            if (vals.isEmpty()) continue;
            sb.append(" AND ").append(e.getKey()).append(" IN (");
            for (int i = 0; i < vals.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(SqlSafeBuilder.literal(vals.get(i)));
            }
            sb.append(") ");
        }
        for (String expr : exprParts) {
            sb.append(" AND ").append(expr).append(" ");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 上下文变量替换：deptId / userId 为数字；deptTree 展开为部门子树 ID 列表 */
    private String substituteContext(String expr, CurrentUser me) {
        if (expr == null) return "";
        String out = expr;
        out = out.replace("${deptId}", me.getDeptId() == null ? "0" : String.valueOf(me.getDeptId()));
        out = out.replace("${userId}", me.getUserId() == null ? "0" : String.valueOf(me.getUserId()));
        if (out.contains("${deptTree}")) {
            out = out.replace("${deptTree}", deptTreeList(me.getDeptId()));
        }
        return out.trim();
    }

    /**
     * 计算部门子树（含自身）的 ID 列表，输出形如 (1,2,3)，供 `dept_id IN ${deptTree}` 使用。
     * 只返回数字，天然防注入。
     */
    private String deptTreeList(Long deptId) {
        if (deptId == null) return "(0)";
        try {
            List<com.rbi.entity.Dept> all = deptMapper.selectList(null);
            java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
            ids.add(deptId);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (com.rbi.entity.Dept d : all) {
                    if (d.getParentId() != null && ids.contains(d.getParentId()) && d.getId() != null
                            && ids.add(d.getId())) {
                        changed = true;
                    }
                }
            }
            StringBuilder sb = new StringBuilder("(");
            int i = 0;
            for (Long id : ids) {
                if (i++ > 0) sb.append(',');
                sb.append(id);
            }
            return sb.append(')').toString();
        } catch (Exception e) {
            log.warn("[数据权限] 部门树解析失败: {}", e.getMessage());
            return "(" + deptId + ")";
        }
    }

    /** 校验表达式：字符集 + 禁止关键字 + 括号配对（上下文变量先按占位值参与校验） */
    public void validateExpression(String expr) {
        if (expr == null || expr.trim().isEmpty()) {
            throw new BizException(400, "数据权限表达式不能为空");
        }
        String e = expr.trim()
                .replace("${deptId}", "0")
                .replace("${userId}", "0")
                .replace("${deptTree}", "(0)");
        if (e.length() > 500) throw new BizException(400, "数据权限表达式过长");
        if (!ALLOWED.matcher(e).matches()) {
            throw new BizException(400, "数据权限表达式包含不允许的字符");
        }
        if (BANNED_KEYWORDS.matcher(e).find()) {
            throw new BizException(400, "数据权限表达式包含不允许的关键字");
        }
        // 函数白名单：任何 ident( 形式都必须是允许的函数（in 是关键字运算符）
        java.util.regex.Matcher fn = java.util.regex.Pattern
                .compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(e);
        while (fn.find()) {
            String name = fn.group(1).toLowerCase(Locale.ROOT);
            if (!ALLOWED_FUNCS.contains(name)) {
                throw new BizException(400, "数据权限表达式使用了不允许的函数: " + name);
            }
        }
        String lower = " " + e.toLowerCase(Locale.ROOT) + " ";
        for (String bad : BANNED) {
            if (lower.contains(bad)) {
                throw new BizException(400, "数据权限表达式包含不允许的片段: " + bad.trim());
            }
        }
        int depth = 0;
        for (char c : e.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth < 0) throw new BizException(400, "数据权限表达式括号不匹配");
        }
        if (depth != 0) throw new BizException(400, "数据权限表达式括号不匹配");
    }
}
