package com.rbi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.config.DataSourceProvider;
import com.rbi.datasource.JdbcExecutor;
import com.rbi.entity.FormDef;
import com.rbi.mapper.FormDefMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 数据填报服务 (M06)：校验 + 批量入库事务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormService {
    private final FormDefMapper formMapper;
    private final JdbcExecutor jdbc;
    private final DataSourceProvider testDb;
    private final ObjectMapper om = new ObjectMapper();

    /** 获取表单定义（字段+校验规则） */
    public FormDef get(Long id) { return formMapper.selectById(id); }

    /** 校验并提交入库 */
    @SuppressWarnings("unchecked")
    public Map<String,Object> submit(Long formId, List<Map<String,Object>> records, String op) {
        FormDef form = formMapper.selectById(formId);
        if (form == null) throw new BizException("表单不存在");
        List<Map<String,Object>> fields;
        try { fields = om.readValue(form.getFieldsJson(), List.class); }
        catch (Exception e) { throw new BizException("表单字段定义解析失败"); }

        if (records == null || records.isEmpty()) throw new BizException("没有可提交的数据");

        // 1. 校验
        List<String> errors = new ArrayList<>();
        for (int i=0;i<records.size();i++) {
            Map<String,Object> rec = records.get(i);
            for (Object fo : fields) {
                Map<String,Object> f = (Map<String,Object>) fo;
                String field = String.valueOf(f.get("field"));
                Object val = rec.get(field);
                boolean required = Boolean.TRUE.equals(f.get("required"));
                if (required && (val==null || String.valueOf(val).trim().isEmpty())) {
                    errors.add("第"+(i+1)+"行 ["+f.get("label")+"] 为必填项");
                    continue;
                }
                if (val != null && "number".equals(f.get("type"))) {
                    try {
                        double n = Double.parseDouble(String.valueOf(val));
                        if (f.get("min")!=null && n < Double.parseDouble(String.valueOf(f.get("min"))))
                            errors.add("第"+(i+1)+"行 ["+f.get("label")+"] 不能小于"+f.get("min"));
                    } catch (NumberFormatException ex) {
                        errors.add("第"+(i+1)+"行 ["+f.get("label")+"] 必须为数字");
                    }
                }
                if (val != null && f.get("regex")!=null && !String.valueOf(val).matches(String.valueOf(f.get("regex")))) {
                    errors.add("第"+(i+1)+"行 ["+f.get("label")+"] 格式不正确");
                }
            }
        }
        if (!errors.isEmpty()) throw new BizException("校验未通过: " + String.join("; ", errors));

        // 2. 构建SQL（参数化转义）
        String table = form.getTableName();
        if (!table.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new BizException("非法表名");
        List<String> sqls = new ArrayList<>();
        for (Map<String,Object> rec : records) {
            if ("delete".equalsIgnoreCase(op)) {
                Object id = rec.get("id");
                if (id != null) sqls.add("DELETE FROM " + table + " WHERE id = " + sql(id));
                continue;
            }
            List<String> cols = new ArrayList<>(), vals = new ArrayList<>();
            for (Map<String,Object> f : fields) {
                String field = String.valueOf(f.get("field"));
                if (field.equals("id") && rec.get("id")==null) continue;
                if (!rec.containsKey(field)) continue;
                cols.add(field);
                vals.add(sql(rec.get(f.get("field")==null?field:field)));
            }
            if ("update".equalsIgnoreCase(op) && rec.get("id")!=null) {
                List<String> sets = new ArrayList<>();
                for (int i=0;i<cols.size();i++) sets.add(cols.get(i)+"="+vals.get(i));
                sqls.add("UPDATE " + table + " SET " + String.join(",", sets) + " WHERE id=" + sql(rec.get("id")));
            } else {
                sqls.add("INSERT INTO " + table + "(" + String.join(",", cols) + ") VALUES (" + String.join(",", vals) + ")");
            }
        }
        int[] affected = jdbc.batch(testDb.driver(), testDb.url(), testDb.username(), testDb.password(), sqls);
        int total = 0; for (int a : affected) if (a>0) total += a;
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("affected", total);
        resp.put("message", "成功提交 " + total + " 条记录");
        return resp;
    }

    /** 查询填报目标表现有数据 */
    public Map<String,Object> data(Long formId) {
        FormDef form = formMapper.selectById(formId);
        if (form == null) throw new BizException("表单不存在");
        String table = form.getTableName();
        if (!table.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new BizException("非法表名");
        List<Map<String,Object>> rows = jdbc.query(testDb.driver(), testDb.url(), testDb.username(), testDb.password(), "SELECT * FROM " + table + " ORDER BY id DESC LIMIT 200");
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("rows", rows);
        resp.put("total", rows.size());
        return resp;
    }

    private String sql(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return String.valueOf(v);
        String s = String.valueOf(v).replace("'", "''");
        return "'" + s + "'";
    }
}
