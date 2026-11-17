package com.rbi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.config.DataSourceProvider;
import com.rbi.datasource.JdbcExecutor;
import com.rbi.entity.FormDef;
import com.rbi.mapper.FormDefMapper;
import com.rbi.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据填报服务 (M06)：校验 + 参数化批量入库
 *
 * 安全改造要点：
 *  1. 所有值一律走 PreparedStatement 占位符，不再拼字符串 + 手工转义；
 *  2. 表名、列名做标识符白名单校验；
 *  3. 单次提交行数上限，避免一条请求打爆数据库事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormService {

    private static final int MAX_ROWS_PER_SUBMIT = 1000;
    private static final String IDENT = "[a-zA-Z_][a-zA-Z0-9_]*";

    private final FormDefMapper formMapper;
    private final JdbcExecutor jdbc;
    private final DataSourceProvider testDb;
    private final ObjectMapper om = new ObjectMapper();

    /** 获取表单定义（字段+校验规则） */
    public FormDef get(Long id) {
        return formMapper.selectById(id);
    }

    /** 校验并提交入库 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> submit(Long formId, List<Map<String, Object>> records, String op) {
        FormDef form = formMapper.selectById(formId);
        if (form == null) throw new BizException("表单不存在");
        List<Map<String, Object>> fields;
        try {
            fields = om.readValue(form.getFieldsJson(), List.class);
        } catch (Exception e) {
            throw new BizException("表单字段定义解析失败");
        }

        if (records == null || records.isEmpty()) throw new BizException("没有可提交的数据");
        if (records.size() > MAX_ROWS_PER_SUBMIT) {
            throw new BizException("单次提交不能超过 " + MAX_ROWS_PER_SUBMIT + " 行");
        }

        String table = form.getTableName();
        if (table == null || !table.matches(IDENT)) throw new BizException("非法表名");
        for (Map<String, Object> f : fields) {
            String field = String.valueOf(f.get("field"));
            if (!field.matches(IDENT)) throw new BizException("非法字段名: " + field);
        }

        // 1. 校验
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> rec = records.get(i);
            if (rec == null) {
                errors.add("第" + (i + 1) + "行数据为空");
                continue;
            }
            for (Map<String, Object> f : fields) {
                String field = String.valueOf(f.get("field"));
                Object val = rec.get(field);
                boolean required = Boolean.TRUE.equals(f.get("required"));
                if (required && (val == null || String.valueOf(val).trim().isEmpty())) {
                    errors.add("第" + (i + 1) + "行 [" + f.get("label") + "] 为必填项");
                    continue;
                }
                if (val == null || String.valueOf(val).trim().isEmpty()) continue;
                if ("number".equals(f.get("type"))) {
                    double n;
                    try {
                        n = Double.parseDouble(String.valueOf(val));
                    } catch (NumberFormatException ex) {
                        errors.add("第" + (i + 1) + "行 [" + f.get("label") + "] 必须为数字");
                        continue;
                    }
                    if (f.get("min") != null && n < Double.parseDouble(String.valueOf(f.get("min")))) {
                        errors.add("第" + (i + 1) + "行 [" + f.get("label") + "] 不能小于" + f.get("min"));
                    }
                    if (f.get("max") != null && n > Double.parseDouble(String.valueOf(f.get("max")))) {
                        errors.add("第" + (i + 1) + "行 [" + f.get("label") + "] 不能大于" + f.get("max"));
                    }
                }
                if (f.get("regex") != null && !String.valueOf(val).matches(String.valueOf(f.get("regex")))) {
                    errors.add("第" + (i + 1) + "行 [" + f.get("label") + "] 格式不正确");
                }
            }
        }
        if (!errors.isEmpty()) throw new BizException("校验未通过: " + String.join("; ", errors));

        // 2. 构建参数化 SQL
        String action = op == null ? "insert" : op.toLowerCase(Locale.ROOT);
        List<String> sqls = new ArrayList<>();
        List<List<Object>> paramsList = new ArrayList<>();

        for (Map<String, Object> rec : records) {
            if ("delete".equals(action)) {
                Object id = rec.get("id");
                if (id != null) {
                    sqls.add("DELETE FROM " + table + " WHERE id = ?");
                    List<Object> ps = new ArrayList<>();
                    ps.add(JdbcExecutor.coerce(id, "number"));
                    paramsList.add(ps);
                }
                continue;
            }
            List<String> cols = new ArrayList<>();
            List<String> vals = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            for (Map<String, Object> f : fields) {
                String field = String.valueOf(f.get("field"));
                if ("id".equals(field) && rec.get("id") == null) continue;
                if (!rec.containsKey(field)) continue;
                cols.add(field);
                params.add(JdbcExecutor.coerce(rec.get(field), String.valueOf(f.get("type"))));
                vals.add("?");
            }
            if (cols.isEmpty()) continue;
            if ("update".equals(action) && rec.get("id") != null) {
                List<String> sets = new ArrayList<>();
                for (String c : cols) {
                    if ("id".equals(c)) continue;
                    sets.add(c + " = ?");
                }
                if (sets.isEmpty()) continue;
                sqls.add("UPDATE " + table + " SET " + String.join(", ", sets) + " WHERE id = ?");
                List<Object> ps = new ArrayList<>();
                int idx = 0;
                for (String c : cols) {
                    if ("id".equals(c)) {
                        idx++;
                        continue;
                    }
                    ps.add(params.get(idx++));
                }
                ps.add(JdbcExecutor.coerce(rec.get("id"), "number"));
                paramsList.add(ps);
            } else {
                sqls.add("INSERT INTO " + table + "(" + String.join(", ", cols) + ") VALUES ("
                        + String.join(", ", vals) + ")");
                paramsList.add(params);
            }
        }
        if (sqls.isEmpty()) throw new BizException("没有可执行的数据变更");

        int[] affected = jdbc.batchWithParams(testDb.driver(), testDb.url(), testDb.username(), testDb.password(),
                sqls, paramsList);
        int total = 0;
        for (int a : affected) if (a > 0) total += a;
        log.info("[填报] 表单 {} 由 {} 提交 {} 行，影响 {} 行", formId, AuthContext.usernameOrSystem(), sqls.size(), total);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("affected", total);
        resp.put("message", "成功提交 " + total + " 条记录");
        return resp;
    }

    /** 查询填报目标表现有数据 */
    public Map<String, Object> data(Long formId) {
        FormDef form = formMapper.selectById(formId);
        if (form == null) throw new BizException("表单不存在");
        String table = form.getTableName();
        if (table == null || !table.matches(IDENT)) throw new BizException("非法表名");
        List<Map<String, Object>> rows = jdbc.query(testDb.driver(), testDb.url(), testDb.username(),
                testDb.password(), "SELECT * FROM " + table + " ORDER BY id DESC LIMIT 200");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("rows", rows);
        resp.put("total", rows.size());
        return resp;
    }
}
