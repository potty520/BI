package com.rbi.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 数据集查询服务 (M02)
 * 负责：SQL 参数安全替换、数据源路由、查询缓存 (M02-04)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetQueryService {

    /** 单次查询行数上限 */
    private static final int MAX_ROWS = 5000;
    /** 缓存条目上限，超出后整体清理，避免无界增长 */
    private static final int MAX_CACHE_ENTRIES = 5000;
    /** 是否已带 LIMIT（必须 DOTALL：SQL 通常是多行） */
    private static final Pattern HAS_LIMIT =
            Pattern.compile("\\blimit\\s+\\d+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final DatasetMapper datasetMapper;
    private final DatasourceMapper datasourceMapper;
    private final JdbcExecutor jdbc;
    private final DataSourceProvider testDb;
    private final DataScopeService dataScopeService;
    private final ColumnPolicyService columnPolicyService;

    /** API 数据集允许访问的主机白名单（空 = 全部禁止，防 SSRF） */
    @Value("${rbi.api-dataset.allowed-hosts:}")
    private String allowedHosts;
    private final ObjectMapper om = new ObjectMapper();

    /** 简易内存缓存：datasetId+规范化参数 -> (结果, 过期时间) */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        List<Map<String, Object>> data;
        long expireAt;
    }

    public List<Map<String, Object>> queryById(Long datasetId, Map<String, Object> params) {
        Dataset ds = datasetMapper.selectById(datasetId);
        if (ds == null) throw new BizException("数据集不存在: " + datasetId);
        return query(ds, params);
    }

    public List<Map<String, Object>> query(Dataset ds, Map<String, Object> params) {
        Map<String, Object> p = params == null ? new HashMap<>() : params;
        // 缓存键必须包含用户身份：行级数据权限会让不同角色拿到不同结果集
        String identity = com.rbi.security.AuthContext.usernameOrSystem();
        String key = ds.getId() + "@" + ds.getDatasourceId() + "@" + identity + "@" + canonical(p);
        boolean cacheable = ds.getCacheSeconds() != null && ds.getCacheSeconds() > 0;
        if (cacheable) {
            CacheEntry ce = cache.get(key);
            if (ce != null && ce.expireAt > System.currentTimeMillis()) return ce.data;
        }

        List<Map<String, Object>> result = run(ds, p);
        // 列级权限/脱敏在返回前统一处理，保证报表、图表、分析、导出看到的是同一份受限数据
        result = columnPolicyService.apply(ds.getId(), result);

        if (cacheable) {
            if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
            CacheEntry ce = new CacheEntry();
            ce.data = result;
            ce.expireAt = System.currentTimeMillis() + ds.getCacheSeconds() * 1000L;
            cache.put(key, ce);
        }
        return result;
    }

    private List<Map<String, Object>> run(Dataset ds, Map<String, Object> p) {
        String type = ds.getType() == null ? "sql" : ds.getType();
        if ("sql".equalsIgnoreCase(type)) return runSql(ds, p);
        if ("api".equalsIgnoreCase(type)) return runApi(ds, p);
        return runConfigData(ds);
    }

    private List<Map<String, Object>> runSql(Dataset ds, Map<String, Object> p) {
        String sql = renderSql(ds, p);
        log.debug("数据集[{}]执行SQL: {}", ds.getName(), sql);
        return executeOn(ds, sql);
    }

    /** 供分析下推等场景复用：返回带参数替换与行级权限的最终 SQL（未追加 LIMIT） */
    public String effectiveSql(Dataset ds, Map<String, Object> params) {
        return renderSql(ds, params == null ? new HashMap<>() : params);
    }

    /** 在数据集所属数据源上执行只读 SQL（调用方负责构造安全的语句） */
    public List<Map<String, Object>> executeOn(Dataset ds, String sql) {
        Datasource source = ds.getDatasourceId() == null ? null : datasourceMapper.selectById(ds.getDatasourceId());
        String url;
        String user;
        String pwd;
        String driver;
        if (source == null) {
            // 未指定数据源时默认走测试业务库
            driver = testDb.driver();
            url = testDb.url();
            user = testDb.username();
            pwd = testDb.password();
        } else {
            driver = source.getDriverClass();
            url = source.getJdbcUrl();
            user = source.getUsername();
            pwd = source.getPasswordEnc();
        }
        return jdbc.query(driver, url, user, pwd, sql);
    }

    private String renderSql(Dataset ds, Map<String, Object> p) {
        // 值型参数
        Map<String, SqlSafeBuilder.Param> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : p.entrySet()) {
            safe.put(e.getKey(), SqlSafeBuilder.Param.val(e.getValue() == null ? "" : String.valueOf(e.getValue())));
        }

        // 片段型占位符：只接受白名单列名 + 字面量转义后的等值条件
        Map<String, String> whitelist = new LinkedHashMap<>();

        // 行级数据权限：有配置才注入；数据集缺少占位符时 fail-closed
        String rowFilter = dataScopeService.rowFilter(ds.getId());
        if (rowFilter != null) {
            String sqlText = ds.getSqlText() == null ? "" : ds.getSqlText();
            if (!sqlText.contains("${row_filter}")) {
                throw new BizException(403, "该数据集未定义 ${row_filter} 占位符，无法应用行级数据权限，请联系管理员");
            }
            whitelist.put("row_filter", rowFilter);
        } else {
            // 无限制时也要占位，否则 ${row_filter} 会被替换成空字面量 '' 导致 SQL 语法错误
            whitelist.put("row_filter", " ");
        }

        whitelist.put("region_filter", eqFragment("region", p.get("region")));
        whitelist.put("date_filter", geDateFragment("order_date", p.get("startDate")));
        for (Map.Entry<String, Object> e : p.entrySet()) {
            String k = e.getKey();
            if (!k.endsWith("_filter") || whitelist.containsKey(k)) continue;
            String col = k.substring(0, k.length() - "_filter".length());
            if (!col.matches("[a-zA-Z_][a-zA-Z0-9_]*")) continue;
            whitelist.put(k, eqFragment(col, e.getValue()));
        }

        String sql = SqlSafeBuilder.build(ds.getSqlText(), safe, whitelist);
        if (!HAS_LIMIT.matcher(sql).find()) sql = sql + " LIMIT " + MAX_ROWS;
        return sql;
    }

    private String eqFragment(String column, Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) return " ";
        return " AND " + column + " = " + SqlSafeBuilder.literal(String.valueOf(value).trim()) + " ";
    }

    private String geDateFragment(String column, Object value) {
        if (value == null) return " ";
        String v = String.valueOf(value).trim();
        if (!v.matches("\\d{4}-\\d{2}-\\d{2}")) return " ";
        return " AND " + column + " >= " + SqlSafeBuilder.literal(v) + " ";
    }

    /**
     * API 数据集：按 config_json 里的 {url, method, headers, body, dataPath} 拉取 JSON。
     * 安全：目标主机必须在 rbi.api-dataset.allowed-hosts 白名单内，默认全禁（防 SSRF）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runApi(Dataset ds, Map<String, Object> p) {
        if (ds.getConfigJson() == null || ds.getConfigJson().trim().isEmpty()) {
            throw new BizException("API 数据集缺少 config_json 配置");
        }
        Map<String, Object> cfg;
        try {
            cfg = om.readValue(ds.getConfigJson(), Map.class);
        } catch (Exception e) {
            throw new BizException("API 数据集配置解析失败: " + e.getMessage());
        }
        // 分页拉取：config_json 里配置 paging 时按页循环抓取
        Map<String, Object> paging = cfg.get("paging") instanceof Map
                ? (Map<String, Object>) cfg.get("paging") : null;
        if (paging != null) return runApiPaged(cfg, p, paging);

        String url = cfg.get("url") == null ? "" : String.valueOf(cfg.get("url"));
        assertAllowedHost(url);

        String method = cfg.get("method") == null ? "GET" : String.valueOf(cfg.get("method")).toUpperCase(Locale.ROOT);
        String body = cfg.get("body") == null ? null : String.valueOf(cfg.get("body"));
        // 参数替换：${name} 用数据集参数值（仅做 URL/请求体文本替换，不做代码执行）
        url = substitute(url, p);
        if (body != null) body = substitute(body, p);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            if (cfg.get("headers") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) cfg.get("headers")).entrySet()) {
                    conn.setRequestProperty(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            if (body != null && !"GET".equals(method)) {
                conn.setDoOutput(true);
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new BizException("API 数据集请求失败: HTTP " + status);
            }
            String text;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            JsonNode root = om.readTree(text);
            String dataPath = cfg.get("dataPath") == null ? "" : String.valueOf(cfg.get("dataPath"));
            JsonNode node = root;
            if (!dataPath.isEmpty()) {
                for (String part : dataPath.split("\\.")) {
                    if (node == null) break;
                    node = node.get(part);
                }
            }
            if (node != null && node.isObject() && node.has("data")) node = node.get("data");
            if (node == null || !node.isArray()) {
                throw new BizException("API 数据集返回的不是数组，请检查 dataPath 配置");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isObject()) rows.add(om.convertValue(item, Map.class));
            }
            if (rows.size() > MAX_ROWS) rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
            return rows;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("API 数据集执行失败: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 分页拉取：paging = {param:"page", start:1, sizeParam:"size", size:100, maxPages:20}
     * 每页请求里 ${page}/${size} 会被替换；某页返回行数小于 size 时停止。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runApiPaged(Map<String, Object> cfg, Map<String, Object> p,
                                                  Map<String, Object> paging) {
        String baseUrl = cfg.get("url") == null ? "" : String.valueOf(cfg.get("url"));
        assertAllowedHost(baseUrl);
        String method = cfg.get("method") == null ? "GET" : String.valueOf(cfg.get("method")).toUpperCase(Locale.ROOT);
        String baseBody = cfg.get("body") == null ? null : String.valueOf(cfg.get("body"));
        String dataPath = cfg.get("dataPath") == null ? "" : String.valueOf(cfg.get("dataPath"));
        Map<String, Object> headers = cfg.get("headers") instanceof Map
                ? (Map<String, Object>) cfg.get("headers") : new HashMap<>();

        int start = intOf(paging.get("start"), 1);
        int size = intOf(paging.get("size"), 100);
        int maxPages = Math.min(intOf(paging.get("maxPages"), 20), 100);
        if (size <= 0) size = 100;

        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 0; i < maxPages; i++) {
            int page = start + i;
            String url = baseUrl.replace("${page}", String.valueOf(page))
                    .replace("${size}", String.valueOf(size))
                    .replace("${offset}", String.valueOf((page - start) * size));
            url = substitute(url, p);
            String body = baseBody == null ? null : substitute(baseBody, p)
                    .replace("${page}", String.valueOf(page))
                    .replace("${size}", String.valueOf(size))
                    .replace("${offset}", String.valueOf((page - start) * size));

            List<Map<String, Object>> rows = fetchOnce(url, method, headers, body, dataPath);
            all.addAll(rows);
            if (rows.size() < size) break;
            if (all.size() >= MAX_ROWS) break;
        }
        if (all.size() > MAX_ROWS) all = new ArrayList<>(all.subList(0, MAX_ROWS));
        log.debug("[API 数据集] 分页拉取 {} 页，共 {} 行", (all.size() + size - 1) / size, all.size());
        return all;
    }

    private int intOf(Object o, int def) {
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 单次 HTTP 请求并解析为行集 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchOnce(String url, String method, Map<String, Object> headers,
                                                String body, String dataPath) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, Object> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), String.valueOf(e.getValue()));
                    }
                }
            }
            if (body != null && !"GET".equals(method)) {
                conn.setDoOutput(true);
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new BizException("API 数据集请求失败: HTTP " + status);
            }
            String text;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            JsonNode root = om.readTree(text);
            JsonNode node = root;
            if (dataPath != null && !dataPath.isEmpty()) {
                for (String part : dataPath.split("\\.")) {
                    if (node == null) break;
                    node = node.get(part);
                }
            }
            if (node != null && node.isObject() && node.has("data")) node = node.get("data");
            if (node == null || !node.isArray()) {
                throw new BizException("API 数据集返回的不是数组，请检查 dataPath 配置");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isObject()) rows.add(om.convertValue(item, Map.class));
            }
            return rows;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("API 数据集执行失败: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void assertAllowedHost(String url) {
        if (url == null || url.trim().isEmpty()) throw new BizException("API 数据集未配置 url");
        String host;
        try {
            host = new URL(url).getHost();
        } catch (Exception e) {
            throw new BizException("API 数据集 url 不合法");
        }
        List<String> allowed = new ArrayList<>();
        if (allowedHosts != null) {
            for (String h : allowedHosts.split(",")) if (!h.trim().isEmpty()) allowed.add(h.trim().toLowerCase(Locale.ROOT));
        }
        if (allowed.isEmpty()) {
            throw new BizException("未配置 rbi.api-dataset.allowed-hosts，API 数据集已禁用（防 SSRF）");
        }
        if (host == null || !allowed.contains(host.toLowerCase(Locale.ROOT))) {
            throw new BizException("目标主机不在白名单内: " + host);
        }
    }

    private String substitute(String text, Map<String, Object> params) {
        String out = text;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", java.net.URLEncoder.encode(
                    e.getValue() == null ? "" : String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runConfigData(Dataset ds) {
        try {
            if (ds.getConfigJson() == null) return new ArrayList<>();
            Map<String, Object> cfg = om.readValue(ds.getConfigJson(), Map.class);
            Object data = cfg.get("data");
            if (data instanceof List) return (List<Map<String, Object>>) data;
        } catch (Exception e) {
            log.warn("解析数据集配置失败", e);
        }
        return new ArrayList<>();
    }

    /** 获取数据集字段列表（执行一次查询推断列） */
    public List<Map<String, Object>> fields(Long datasetId) {
        List<Map<String, Object>> rows = queryById(datasetId, new HashMap<>());
        List<Map<String, Object>> fields = new ArrayList<>();
        if (rows.isEmpty()) return fields;
        for (String k : rows.get(0).keySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", k);
            Object v = rows.get(0).get(k);
            f.put("type", v instanceof Number ? "number" : (v instanceof java.util.Date ? "date" : "string"));
            fields.add(f);
        }
        return fields;
    }

    public void clearCache(Long datasetId) {
        if (datasetId == null) return;
        String prefix = datasetId + "@";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 参数规范化：键排序后序列化，保证同一组参数得到同一个键 */
    private String canonical(Map<String, Object> p) {
        try {
            return om.writeValueAsString(new TreeMap<>(p));
        } catch (Exception e) {
            return String.valueOf(p.size());
        }
    }
}
