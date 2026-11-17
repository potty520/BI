package com.rbi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.entity.Dataset;
import com.rbi.mapper.DatasetMapper;
import com.rbi.datasource.JdbcExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 自助分析服务 (M07)：分析配置 -> SQL 自动翻译引擎
 * 配置格式：{ rows:[字段], cols:[字段], values:[{field,agg}], filters:[{field,op,value}], sorts:[{field,dir}], quick:{type} }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {
    private final DatasetMapper datasetMapper;
    private final DatasetQueryService datasetQuery;
    private final ObjectMapper om = new ObjectMapper();

    /** 直接在明细数据上做本地聚合（演示可靠，且不依赖被测库SQL方言） */
    @SuppressWarnings("unchecked")
    public Map<String,Object> analyze(Long datasetId, Map<String,Object> config) {
        Dataset ds = datasetMapper.selectById(datasetId);
        if (ds == null) throw new BizException("数据集不存在");
        List<Map<String,Object>> rows = datasetQuery.queryById(datasetId, new HashMap<>());

        List<String> dims = new ArrayList<>();
        if (config.get("rows") instanceof List) dims.addAll((List<String>) config.get("rows"));
        if (config.get("cols") instanceof List) for (Object c : (List<?>)config.get("cols")) if (!dims.contains(c)) dims.add(String.valueOf(c));

        List<Map<String,Object>> measures = new ArrayList<>();
        if (config.get("values") instanceof List) measures.addAll((List<Map<String,Object>>) config.get("values"));
        if (measures.isEmpty()) measures.add(map("field","amount","agg","SUM"));

        // 过滤
        if (config.get("filters") instanceof List) {
            for (Object fo : (List<?>)config.get("filters")) {
                Map<String,Object> f = (Map<String,Object>) fo;
                String field = String.valueOf(f.get("field"));
                String op = String.valueOf(f.getOrDefault("op","eq"));
                Object val = f.get("value");
                if (val==null || String.valueOf(val).isEmpty()) continue;
                rows.removeIf(r -> !match(r.get(field), op, val));
            }
        }

        // 分组聚合
        Map<String, List<Map<String,Object>>> groups = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            StringBuilder key = new StringBuilder();
            for (String d : dims) key.append("|").append(String.valueOf(row.getOrDefault(d,"")));
            groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(row);
        }

        List<Map<String,Object>> result = new ArrayList<>();
        for (Map.Entry<String,List<Map<String,Object>>> e : groups.entrySet()) {
            Map<String,Object> out = new LinkedHashMap<>();
            String[] parts = e.getKey().substring(1).split("\\|", -1);
            for (int i=0;i<dims.size();i++) out.put(dims.get(i), parts.length>i?parts[i]:"");
            for (Map<String,Object> m : measures) {
                String field = String.valueOf(m.get("field"));
                String agg = String.valueOf(m.getOrDefault("agg","SUM")).toUpperCase();
                out.put(agg+"_"+field, aggregate(e.getValue(), field, agg));
            }
            result.add(out);
        }

        // 排序
        if (config.get("sorts") instanceof List && !((List<?>)config.get("sorts")).isEmpty()) {
            Map<String,Object> s = (Map<String,Object>)((List<?>)config.get("sorts")).get(0);
            String field = String.valueOf(s.get("field"));
            boolean asc = !"desc".equalsIgnoreCase(String.valueOf(s.get("dir")));
            final boolean a = asc;
            result.sort((x,y) -> {
                Object vx=x.get(field), vy=y.get(field);
                int c;
                try { c = Double.compare(Double.parseDouble(String.valueOf(vx)), Double.parseDouble(String.valueOf(vy))); }
                catch (Exception ex) { c = String.valueOf(vx).compareTo(String.valueOf(vy)); }
                return a?c:-c;
            });
        }

        // 快速计算 M07-04：占比 / 排名 / 累计
        String quick = config.get("quick")==null?"":String.valueOf(config.get("quick"));
        if (!quick.isEmpty() && !measures.isEmpty()) {
            String mk = String.valueOf(measures.get(0).getOrDefault("agg","SUM")).toUpperCase()+"_"+measures.get(0).get("field");
            double total = 0; for (Map<String,Object> r : result) total += toDouble(r.get(mk));
            if ("ratio".equals(quick)) for (Map<String,Object> r : result) r.put("占比", total==0?0:toDouble(r.get(mk))/total*100);
            if ("rank".equals(quick)) {
                List<Map<String,Object>> sorted = new ArrayList<>(result);
                sorted.sort((x,y)->Double.compare(toDouble(y.get(mk)),toDouble(x.get(mk))));
                for (int i=0;i<sorted.size();i++) sorted.get(i).put("排名", i+1);
            }
            if ("cumulative".equals(quick)) {
                double cum=0; for (Map<String,Object> r : result) { cum+=toDouble(r.get(mk)); r.put("累计", cum); }
            }
        }

        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("columns", buildColumns(dims, measures));
        resp.put("rows", result);
        resp.put("total", result.size());
        return resp;
    }

    private List<Map<String,Object>> buildColumns(List<String> dims, List<Map<String,Object>> measures) {
        List<Map<String,Object>> cols = new ArrayList<>();
        for (String d : dims) cols.add(map("name",d,"label",d,"type","dim"));
        for (Map<String,Object> m : measures) {
            String k = String.valueOf(m.getOrDefault("agg","SUM")).toUpperCase()+"_"+m.get("field");
            cols.add(map("name",k,"label",k,"type","measure"));
        }
        return cols;
    }

    private Object aggregate(List<Map<String,Object>> rows, String field, String agg) {
        List<Double> nums = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            try { nums.add(Double.parseDouble(String.valueOf(r.get(field)))); } catch (Exception ignored) {}
        }
        if (nums.isEmpty()) return 0;
        double s=0; for(double n:nums) s+=n;
        switch (agg) {
            case "AVG": return s/nums.size();
            case "COUNT": return rows.size();
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
            case "gt": return toDouble(actual)>toDouble(expected);
            case "lt": return toDouble(actual)<toDouble(expected);
            default: return a.equals(e);
        }
    }

    private double toDouble(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; } }

    /** 生成等价SQL（用于展示"配置->SQL"翻译结果，M07 自研要点） */
    public String toSql(Map<String,Object> config) {
        @SuppressWarnings("unchecked")
        List<String> dims = config.get("rows") instanceof List ? (List<String>)config.get("rows") : new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> values = config.get("values") instanceof List ? (List<Map<String,Object>>)config.get("values") : new ArrayList<>();
        StringBuilder sb = new StringBuilder("SELECT ");
        if (dims.isEmpty()) sb.append("*");
        else sb.append(String.join(", ", dims));
        if (!values.isEmpty() && !dims.isEmpty()) sb.append(", ");
        for (int i=0;i<values.size();i++) {
            Map<String,Object> m = values.get(i);
            if (i>0) sb.append(", ");
            sb.append(String.valueOf(m.getOrDefault("agg","SUM")).toUpperCase()).append("(")
              .append(m.get("field")).append(") AS ").append(m.getOrDefault("agg","SUM")).append("_").append(m.get("field"));
        }
        sb.append(" FROM dataset WHERE 1=1 ");
        if (!dims.isEmpty()) sb.append(" GROUP BY ").append(String.join(", ", dims));
        return sb.toString();
    }

    private Map<String,Object> map(Object... kv) {
        Map<String,Object> m = new LinkedHashMap<>();
        for (int i=0;i<kv.length;i+=2) m.put(String.valueOf(kv[i]), kv[i+1]);
        return m;
    }
}
