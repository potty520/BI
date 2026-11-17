package com.rbi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.datasource.SqlSafeBuilder;
import com.rbi.entity.Dataset;
import com.rbi.mapper.DatasetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自助分析服务 (M07)：分析配置 -> SQL 聚合下推。
 *
 * 配置格式：
 * { rows:[字段], cols:[字段], values:[{field,agg}],
 *   filters:[{field,op,value}], sorts:[{field,dir}], quick:{ratio|rank|cumulative},
 *   engine: "auto" | "sql" | "memory" }
 *
 * 引擎说明：
 *  - sql    ：把分组聚合下推到数据库执行（默认 auto 优先）；
 *  - memory ：拉取明细后在 Java 内存聚合（原实现，保留用于兼容和不支持方言的场景）；
 *  - auto   ：先试 SQL，失败自动回退内存，并在返回体里标注实际使用的引擎。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final List<String> AGGS = Arrays.asList("SUM", "AVG", "COUNT", "MAX", "MIN");
    private static final String IDENT = "[a-zA-Z_][a-zA-Z0-9_]*";
    private static final int MAX_ROWS = 5000;

    private final DatasetMapper datasetMapper;
    private final DatasetQueryService datasetQuery;
    private final ColumnPolicyService columnPolicyService;
    private final ObjectMapper om = new ObjectMapper();

    public Map<String, Object> analyze(Long datasetId, Map<String, Object> config) {
        Dataset ds = datasetMapper.selectById(datasetId);
        if (ds == null) throw new BizException("数据集不存在");
        Map<String, Object> cfg = config == null ? new HashMap<>() : config;

        // 列级权限：被隐藏的列不允许参与分组/聚合（否则等于绕过列权限推断出原值）
        java.util.Set<String> hidden = columnPolicyService.hiddenColumns(datasetId);
        if (!hidden.isEmpty()) {
            for (String d : dimensions(cfg)) {
                if (hidden.contains(d.toLowerCase(Locale.ROOT))) throw new BizException(403, "无权使用字段: " + d);
            }
            for (Map<String, Object> m : measures(cfg)) {
                String f = String.valueOf(m.get("field")).toLowerCase(Locale.ROOT);
                if (hidden.contains(f)) throw new BizException(403, "无权使用字段: " + f);
            }
        }

        String engine = cfg.get("engine") == null ? "auto" : String.valueOf(cfg.get("engine")).toLowerCase(Locale.ROOT);
        boolean sqlCapable = ds.getType() == null || "sql".equalsIgnoreCase(ds.getType());

        if (!"memory".equals(engine) && sqlCapable) {
            try {
                return analyzeBySql(ds, cfg);
            } catch (Exception e) {
                if ("sql".equals(engine)) throw e;
                log.warn("[分析] SQL 下推失败，回退内存聚合: {}", e.getMessage());
            }
        }
        Map<String, Object> resp = analyzeInMemory(ds, cfg);
        resp.put("engine", "memory");
        return resp;
    }

    // ---------------- SQL 下推 ----------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzeBySql(Dataset ds, Map<String, Object> config) {
        List<String> dims = dimensions(config);
        List<Map<String, Object>> measures = measures(config);

        String base = datasetQuery.effectiveSql(ds, asParamMap(config.get("params")));
        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> selects = new ArrayList<>();
        for (String d : dims) selects.add(d);
        for (Map<String, Object> m : measures) {
            String field = ident(String.valueOf(m.get("field")));
            String agg = agg(String.valueOf(m.getOrDefault("agg", "SUM")));
            selects.add(agg + "(" + field + ") AS " + agg + "_" + field);
        }
        sql.append(String.join(", ", selects));
        sql.append(" FROM (").append(base).append(") rbi_t");

        String where = whereClause(config);
        if (!where.isEmpty()) sql.append(" WHERE 1=1 ").append(where);
        if (!dims.isEmpty()) sql.append(" GROUP BY ").append(String.join(", ", dims));
        String order = orderClause(config);
        if (!order.isEmpty()) sql.append(" ORDER BY ").append(order);
        sql.append(" LIMIT ").append(MAX_ROWS);

        List<Map<String, Object>> rows = datasetQuery.executeOn(ds, sql.toString());
        rows = applyQuick(rows, config, measures);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("columns", buildColumns(dims, measures));
        resp.put("rows", rows);
        resp.put("total", rows.size());
        resp.put("engine", "sql");
        resp.put("sql", sql.toString());
        return resp;
    }

    @SuppressWarnings("unchecked")
    private String whereClause(Map<String, Object> config) {
        if (!(config.get("filters") instanceof List)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object fo : (List<?>) config.get("filters")) {
            Map<String, Object> f = (Map<String, Object>) fo;
            Object val = f.get("value");
            if (val == null || String.valueOf(val).trim().isEmpty()) continue;
            String field = ident(String.valueOf(f.get("field")));
            String op = String.valueOf(f.getOrDefault("op", "eq")).toLowerCase(Locale.ROOT);
            String literal;
            if (String.valueOf(val).matches("-?\\d+(\\.\\d+)?")) {
                literal = String.valueOf(val);
            } else {
                literal = SqlSafeBuilder.literal(String.valueOf(val));
            }
            switch (op) {
                case "ne": sb.append(" AND ").append(field).append(" <> ").append(literal); break;
                case "gt": sb.append(" AND ").append(field).append(" > ").append(literal); break;
                case "lt": sb.append(" AND ").append(field).append(" < ").append(literal); break;
                case "contains":
                    sb.append(" AND ").append(field).append(" LIKE ")
                      .append(SqlSafeBuilder.literal("%" + val + "%")); break;
                default: sb.append(" AND ").append(field).append(" = ").append(literal);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String orderClause(Map<String, Object> config) {
        if (!(config.get("sorts") instanceof List) || ((List<?>) config.get("sorts")).isEmpty()) return "";
        Map<String, Object> s = (Map<String, Object>) ((List<?>) config.get("sorts")).get(0);
        String field = ident(String.valueOf(s.get("field")));
        boolean asc = !"desc".equalsIgnoreCase(String.valueOf(s.get("dir")));
        return field + (asc ? " ASC" : " DESC");
    }

    private Map<String, Object> asParamMap(Object o) {
        if (o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            return m;
        }
        return new HashMap<>();
    }

    // ---------------- 内存聚合（原实现） ----------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzeInMemory(Dataset ds, Map<String, Object> config) {
        List<Map<String, Object>> rows = datasetQuery.queryById(ds.getId(), asParamMap(config.get("params")));
        List<String> dims = dimensions(config);
        List<Map<String, Object>> measures = measures(config);

        // 过滤
        if (config.get("filters") instanceof List) {
            for (Object fo : (List<?>) config.get("filters")) {
                Map<String, Object> f = (Map<String, Object>) fo;
                String field = String.valueOf(f.get("field"));
                String op = String.valueOf(f.getOrDefault("op", "eq"));
                Object val = f.get("value");
                if (val == null || String.valueOf(val).isEmpty()) continue;
                rows.removeIf(r -> !match(r.get(field), op, val));
            }
        }

        // 分组聚合
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            StringBuilder key = new StringBuilder();
            for (String d : dims) key.append("|").append(String.valueOf(row.getOrDefault(d, "")));
            groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
            Map<String, Object> out = new LinkedHashMap<>();
            String[] parts = e.getKey().substring(1).split("\\|", -1);
            for (int i = 0; i < dims.size(); i++) out.put(dims.get(i), parts.length > i ? parts[i] : "");
            for (Map<String, Object> m : measures) {
                String field = String.valueOf(m.get("field"));
                String agg = agg(String.valueOf(m.getOrDefault("agg", "SUM")));
                out.put(agg + "_" + field, aggregate(e.getValue(), field, agg));
            }
            result.add(out);
        }

        // 排序
        if (config.get("sorts") instanceof List && !((List<?>) config.get("sorts")).isEmpty()) {
            Map<String, Object> s = (Map<String, Object>) ((List<?>) config.get("sorts")).get(0);
            String field = String.valueOf(s.get("field"));
            boolean asc = !"desc".equalsIgnoreCase(String.valueOf(s.get("dir")));
            final boolean a = asc;
            result.sort((x, y) -> {
                Object vx = x.get(field), vy = y.get(field);
                int c;
                try {
                    c = Double.compare(Double.parseDouble(String.valueOf(vx)), Double.parseDouble(String.valueOf(vy)));
                } catch (Exception ex) {
                    c = String.valueOf(vx).compareTo(String.valueOf(vy));
                }
                return a ? c : -c;
            });
        }

        result = applyQuick(result, config, measures);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("columns", buildColumns(dims, measures));
        resp.put("rows", result);
        resp.put("total", result.size());
        return resp;
    }

    /** 快速计算：占比 / 排名 / 累计（两种引擎共用） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> applyQuick(List<Map<String, Object>> result, Map<String, Object> config,
                                                 List<Map<String, Object>> measures) {
        String quick = config.get("quick") == null ? "" : String.valueOf(config.get("quick"));
        if (quick.isEmpty() || measures.isEmpty()) return result;
        Map<String, Object> first = measures.get(0);
        String mk = agg(String.valueOf(first.getOrDefault("agg", "SUM"))) + "_" + String.valueOf(first.get("field"));
        double total = 0;
        for (Map<String, Object> r : result) total += toDouble(r.get(mk));
        if ("ratio".equals(quick)) {
            for (Map<String, Object> r : result) r.put("占比", total == 0 ? 0 : Math.round(toDouble(r.get(mk)) / total * 10000) / 100.0);
        }
        if ("rank".equals(quick)) {
            List<Map<String, Object>> sorted = new ArrayList<>(result);
            sorted.sort((x, y) -> Double.compare(toDouble(y.get(mk)), toDouble(x.get(mk))));
            for (int i = 0; i < sorted.size(); i++) sorted.get(i).put("排名", i + 1);
        }
        if ("cumulative".equals(quick)) {
            double cum = 0;
            for (Map<String, Object> r : result) {
                cum += toDouble(r.get(mk));
                r.put("累计", Math.round(cum * 100) / 100.0);
            }
        }
        return result;
    }

    // ---------------- 公共 ----------------

    @SuppressWarnings("unchecked")
    private List<String> dimensions(Map<String, Object> config) {
        List<String> dims = new ArrayList<>();
        if (config.get("rows") instanceof List) {
            for (Object o : (List<?>) config.get("rows")) {
                String d = ident(String.valueOf(o));
                if (!dims.contains(d)) dims.add(d);
            }
        }
        if (config.get("cols") instanceof List) {
            for (Object o : (List<?>) config.get("cols")) {
                String d = ident(String.valueOf(o));
                if (!dims.contains(d)) dims.add(d);
            }
        }
        return dims;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> measures(Map<String, Object> config) {
        List<Map<String, Object>> measures = new ArrayList<>();
        if (config.get("values") instanceof List) measures.addAll((List<Map<String, Object>>) config.get("values"));
        if (measures.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", "amount");
            m.put("agg", "SUM");
            measures.add(m);
        }
        return measures;
    }

    private String ident(String s) {
        if (s == null || !s.matches(IDENT)) throw new BizException("非法字段名: " + s);
        return s;
    }

    private String agg(String a) {
        String u = a == null ? "SUM" : a.toUpperCase(Locale.ROOT);
        if (!AGGS.contains(u)) throw new BizException("不支持的聚合函数: " + a);
        return u;
    }

    private List<Map<String, Object>> buildColumns(List<String> dims, List<Map<String, Object>> measures) {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (String d : dims) cols.add(map("name", d, "label", d, "type", "dim"));
        for (Map<String, Object> m : measures) {
            String k = agg(String.valueOf(m.getOrDefault("agg", "SUM"))) + "_" + m.get("field");
            cols.add(map("name", k, "label", k, "type", "measure"));
        }
        return cols;
    }

    private Object aggregate(List<Map<String, Object>> rows, String field, String agg) {
        List<Double> nums = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            try {
                nums.add(Double.parseDouble(String.valueOf(r.get(field))));
            } catch (Exception ignored) {
            }
        }
        if ("COUNT".equals(agg)) return rows.size();
        if (nums.isEmpty()) return 0;
        double s = 0;
        for (double n : nums) s += n;
        switch (agg) {
            case "AVG": return s / nums.size();
            case "MAX": return Collections.max(nums);
            case "MIN": return Collections.min(nums);
            default: return s;
        }
    }

    private boolean match(Object actual, String op, Object expected) {
        String a = String.valueOf(actual), e = String.valueOf(expected);
        switch (op) {
            case "ne": return !a.equals(e);
            case "contains": return a.contains(e);
            case "gt": return toDouble(actual) > toDouble(expected);
            case "lt": return toDouble(actual) < toDouble(expected);
            default: return a.equals(e);
        }
    }

    private double toDouble(Object o) {
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 生成等价 SQL 供界面展示。
     * 传入 datasetId 时基于数据集真实 SQL（含行级权限）拼装，可用于直接执行；
     * 未传时退化为模板，仅作说明用途。
     */
    @SuppressWarnings("unchecked")
    public String toSql(Long datasetId, Map<String, Object> config) {
        List<String> dims = dimensions(config);
        List<Map<String, Object>> measures = measures(config);
        StringBuilder sb = new StringBuilder("SELECT ");
        List<String> selects = new ArrayList<>(dims);
        for (Map<String, Object> m : measures) {
            String field = ident(String.valueOf(m.get("field")));
            String agg = agg(String.valueOf(m.getOrDefault("agg", "SUM")));
            selects.add(agg + "(" + field + ") AS " + agg + "_" + field);
        }
        sb.append(String.join(", ", selects));

        String base = null;
        if (datasetId != null) {
            Dataset ds = datasetMapper.selectById(datasetId);
            if (ds != null) base = datasetQuery.effectiveSql(ds, asParamMap(config.get("params")));
        }
        sb.append(" FROM ").append(base == null ? "(<数据集 SQL>) rbi_t" : "(" + base + ") rbi_t");
        String where = whereClause(config);
        if (!where.isEmpty()) sb.append(" WHERE 1=1 ").append(where);
        if (!dims.isEmpty()) sb.append(" GROUP BY ").append(String.join(", ", dims));
        String order = orderClause(config);
        if (!order.isEmpty()) sb.append(" ORDER BY ").append(order);
        if (base != null) sb.append(" LIMIT ").append(MAX_ROWS);
        return sb.toString();
    }

    /** 兼容旧调用 */
    public String toSql(Map<String, Object> config) {
        return toSql(null, config);
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
