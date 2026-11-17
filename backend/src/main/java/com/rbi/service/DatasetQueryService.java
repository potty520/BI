package com.rbi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.config.DataSourceProvider;
import com.rbi.datasource.JdbcExecutor;
import com.rbi.datasource.SqlSafeBuilder;
import com.rbi.entity.Dataset;
import com.rbi.entity.Datasource;
import com.rbi.mapper.DatasetMapper;
import com.rbi.mapper.DatasourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 数据集查询服务 (M02)
 * 负责：SQL参数安全替换、数据源路由、查询缓存(M02-04)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetQueryService {
    private final DatasetMapper datasetMapper;
    private final DatasourceMapper datasourceMapper;
    private final JdbcExecutor jdbc;
    private final DataSourceProvider testDb;
    private final ObjectMapper om = new ObjectMapper();

    /** 简易内存缓存：datasetId+参数hash -> (结果, 过期时间) */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static class CacheEntry { List<Map<String,Object>> data; long expireAt; }

    public List<Map<String,Object>> queryById(Long datasetId, Map<String,Object> params) {
        Dataset ds = datasetMapper.selectById(datasetId);
        if (ds == null) throw new BizException("数据集不存在: " + datasetId);
        return query(ds, params);
    }

    public List<Map<String,Object>> query(Dataset ds, Map<String,Object> params) {
        Map<String,Object> p = params == null ? new HashMap<>() : params;
        String key = ds.getId() + "_" + p.hashCode();
        if (ds.getCacheSeconds() != null && ds.getCacheSeconds() > 0) {
            CacheEntry ce = cache.get(key);
            if (ce != null && ce.expireAt > System.currentTimeMillis()) return ce.data;
        }

        List<Map<String,Object>> result;
        if ("sql".equalsIgnoreCase(ds.getType()) || ds.getType()==null) {
            result = runSql(ds, p);
        } else if ("api".equalsIgnoreCase(ds.getType())) {
            result = runApi(ds, p);
        } else {
            // visual / file 数据集：config_json 中可存放预置数据
            result = runConfigData(ds);
        }

        if (ds.getCacheSeconds() != null && ds.getCacheSeconds() > 0) {
            CacheEntry ce = new CacheEntry();
            ce.data = result;
            ce.expireAt = System.currentTimeMillis() + ds.getCacheSeconds()*1000L;
            cache.put(key, ce);
        }
        return result;
    }

    private List<Map<String,Object>> runSql(Dataset ds, Map<String,Object> p) {
        Datasource source = ds.getDatasourceId()==null ? null : datasourceMapper.selectById(ds.getDatasourceId());
        String url, user, pwd, driver;
        if (source == null) {
            // 未指定数据源时默认走测试业务库
            driver = testDb.driver(); url = testDb.url();
            user = testDb.username(); pwd = testDb.password();
        } else {
            driver = source.getDriverClass(); url = source.getJdbcUrl();
            user = source.getUsername(); pwd = source.getPasswordEnc();
        }

        // 构建安全参数
        Map<String, SqlSafeBuilder.Param> safe = new HashMap<>();
        for (Map.Entry<String,Object> e : p.entrySet()) {
            safe.put(e.getKey(), SqlSafeBuilder.Param.val(e.getValue()==null?"":String.valueOf(e.getValue())));
        }
        // 片段型占位符白名单：仅在值合法时注入固定 SQL 片段，杜绝任意拼接
        Map<String,String> whitelist = new HashMap<>();
        Object region = p.get("region");
        if (region != null && !String.valueOf(region).trim().isEmpty()) {
            String r = String.valueOf(region).replace("'","''");
            whitelist.put("region_filter", " AND region = '" + r + "' ");
        } else whitelist.put("region_filter", " ");
        Object start = p.get("startDate");
        if (start != null && !String.valueOf(start).trim().isEmpty() && String.valueOf(start).matches("\\d{4}-\\d{2}-\\d{2}")) {
            whitelist.put("date_filter", " AND order_date >= '" + start + "' ");
        } else whitelist.put("date_filter", " ");
        // 通用 ${field_filter} 白名单
        for (Map.Entry<String,Object> e : p.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("_filter") && !whitelist.containsKey(k)) {
                String col = k.substring(0, k.length()-7);
                if (col.matches("[a-zA-Z_][a-zA-Z0-9_]*") && e.getValue()!=null && !String.valueOf(e.getValue()).isEmpty()) {
                    String v = String.valueOf(e.getValue()).replace("'","''");
                    whitelist.put(k, " AND " + col + " = '" + v + "' ");
                } else whitelist.put(k, " ");
            }
        }

        String sql = SqlSafeBuilder.build(ds.getSqlText(), safe, whitelist);
        log.info("数据集[{}]执行SQL: {}", ds.getName(), sql);
        int limit = 5000;
        if (!sql.toLowerCase().matches(".*\\blimit\\s+\\d+.*")) sql = sql + " LIMIT " + limit;
        return jdbc.query(driver, url, user, pwd, sql);
    }

    private List<Map<String,Object>> runApi(Dataset ds, Map<String,Object> p) {
        throw new BizException("API数据集执行器需在联网环境启用；当前演示数据使用内置SQL数据集");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> runConfigData(Dataset ds) {
        try {
            if (ds.getConfigJson()==null) return new ArrayList<>();
            Map<String,Object> cfg = om.readValue(ds.getConfigJson(), Map.class);
            Object data = cfg.get("data");
            if (data instanceof List) return (List<Map<String,Object>>) data;
        } catch (Exception e) { log.warn("解析数据集配置失败", e); }
        return new ArrayList<>();
    }

    /** 获取数据集字段列表（执行 LIMIT 1 推断列） */
    public List<Map<String,Object>> fields(Long datasetId) {
        List<Map<String,Object>> rows = queryById(datasetId, new HashMap<>());
        List<Map<String,Object>> fields = new ArrayList<>();
        if (rows.isEmpty()) return fields;
        for (String k : rows.get(0).keySet()) {
            Map<String,Object> f = new LinkedHashMap<>();
            f.put("name", k);
            Object v = rows.get(0).get(k);
            f.put("type", v instanceof Number ? "number" : (v instanceof java.util.Date ? "date" : "string"));
            fields.add(f);
        }
        return fields;
    }

    public void clearCache(Long datasetId) {
        cache.keySet().removeIf(k -> k.startsWith(datasetId+"_"));
    }
}
