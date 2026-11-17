package com.rbi.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.common.R;
import com.rbi.config.DataSourceProvider;
import com.rbi.datasource.JdbcExecutor;
import com.rbi.engine.ExpandEngine;
import com.rbi.export.ExportService;
import com.rbi.export.PdfExportService;
import com.rbi.entity.Chart;
import com.rbi.entity.Dashboard;
import com.rbi.entity.Dataset;
import com.rbi.entity.Datasource;
import com.rbi.entity.ParamDef;
import com.rbi.entity.Report;
import com.rbi.mapper.ChartMapper;
import com.rbi.mapper.DashboardMapper;
import com.rbi.mapper.DatasetMapper;
import com.rbi.mapper.DatasourceMapper;
import com.rbi.mapper.ParamDefMapper;
import com.rbi.mapper.ReportMapper;
import com.rbi.security.RequireRole;
import com.rbi.service.AnalysisService;
import com.rbi.service.DatasetQueryService;
import com.rbi.service.FormService;
import com.rbi.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 核心业务控制器：数据源 / 数据集 / 报表 / 参数 / 分析 / 填报 / 图表
 *
 * 权限约定：
 *  - 读取类接口：任意已登录用户；
 *  - 报表/数据集/数据源/参数/图表的"配置类"写操作：ADMIN 或 ANALYST；
 *  - 数据源连接测试、任意 SQL 执行：仅 ADMIN（这两个能力等价于服务器侧网络与数据库访问）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DataController {

    private final DatasourceMapper dsMapper;
    private final DatasetMapper datasetMapper;
    private final ReportMapper reportMapper;
    private final ParamDefMapper paramMapper;
    private final JdbcExecutor jdbc;
    private final DataSourceProvider testDb;
    private final DatasetQueryService datasetQuery;
    private final ReportService reportService;
    private final AnalysisService analysisService;
    private final FormService formService;
    private final ExportService exportService;
    private final PdfExportService pdfExportService;
    private final ChartMapper chartMapper;
    private final DashboardMapper dashboardMapper;
    private final ObjectMapper om = new ObjectMapper();

    // ---------- M01 数据源 ----------
    @GetMapping("/datasources")
    public R<?> datasources() {
        List<Datasource> list = dsMapper.selectList(null);
        // 不回传口令字段
        for (Datasource d : list) d.setPasswordEnc(null);
        return R.ok(list);
    }

    @PostMapping("/datasources")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> saveDatasource(@RequestBody Datasource d) {
        dsMapper.save(d);
        return R.ok(d);
    }

    @DeleteMapping("/datasources/{id}")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> delDatasource(@PathVariable Long id) {
        dsMapper.deleteById(id);
        return R.ok();
    }

    /** 连接测试会发起服务器侧外联，仅管理员可用 */
    @PostMapping("/datasources/test")
    @RequireRole("ADMIN")
    public R<?> testDatasource(@RequestBody Datasource d) {
        jdbc.testConnection(d.getDriverClass(), d.getJdbcUrl(), d.getUsername(), d.getPasswordEnc());
        return R.ok("连接成功");
    }

    @GetMapping("/datasource/drivers")
    public R<?> drivers() {
        return R.ok(JdbcExecutor.DRIVERS);
    }

    // ---------- M02 数据集 ----------
    @GetMapping("/datasets")
    public R<?> datasets() {
        return R.ok(datasetMapper.selectList(null));
    }

    @PostMapping("/datasets")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> saveDataset(@RequestBody Dataset d) {
        if (d.getId() == null) datasetMapper.insert(d);
        else datasetMapper.updateById(d);
        datasetQuery.clearCache(d.getId());
        return R.ok(d);
    }

    @DeleteMapping("/datasets/{id}")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> delDataset(@PathVariable Long id) {
        datasetMapper.deleteById(id);
        datasetQuery.clearCache(id);
        return R.ok();
    }

    @GetMapping("/datasets/{id}")
    public R<?> getDataset(@PathVariable Long id) {
        return R.ok(datasetMapper.selectById(id));
    }

    @PostMapping("/datasets/{id}/query")
    public R<?> queryDataset(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params) {
        return R.ok(datasetQuery.queryById(id, params));
    }

    @GetMapping("/datasets/{id}/fields")
    public R<?> datasetFields(@PathVariable Long id) {
        return R.ok(datasetQuery.fields(id));
    }

    /**
     * 临时 SQL 执行（调试用）。仅管理员可用，且强制只读单条语句。
     * 该接口不再接受任意 DDL/DML，避免被当作数据库写入口。
     */
    @PostMapping("/sql/execute")
    @RequireRole("ADMIN")
    public R<?> executeSql(@RequestBody Map<String, Object> body) {
        String raw = body == null || body.get("sql") == null ? "" : String.valueOf(body.get("sql")).trim();
        assertReadOnly(raw);
        Dataset d = new Dataset();
        d.setId(-1L);
        d.setType("sql");
        d.setSqlText(raw);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = body.get("params") instanceof Map ? (Map<String, Object>) body.get("params") : new HashMap<>();
        return R.ok(datasetQuery.query(d, p));
    }

    private void assertReadOnly(String sql) {
        if (sql.isEmpty()) throw new BizException(400, "SQL 不能为空");
        String normalized = sql.replaceAll("/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ").trim();
        if (normalized.contains(";")) throw new BizException(400, "只允许执行单条语句");
        String head = normalized.toLowerCase(Locale.ROOT);
        if (!head.startsWith("select") && !head.startsWith("with")) {
            throw new BizException(400, "只允许只读查询（SELECT / WITH）");
        }
        // 常见写操作关键字兜底（子查询里夹带 DML 的情况）
        for (String kw : new String[]{" into ", " delete ", " update ", " insert ", " drop ", " truncate ", " alter "}) {
            if ((" " + head + " ").contains(kw)) {
                throw new BizException(400, "只允许只读查询，检测到写操作关键字");
            }
        }
    }

    // ---------- M03 报表 ----------
    @GetMapping("/reports")
    public R<?> reports() {
        return R.ok(reportService.listWithDatasetName());
    }

    @GetMapping("/reports/{id}")
    public R<?> getReport(@PathVariable Long id) {
        return R.ok(reportMapper.selectById(id));
    }

    @PostMapping("/reports")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> saveReport(@RequestBody Map<String, Object> body) {
        try {
            Report r = om.readValue(om.writeValueAsString(body.get("report")), Report.class);
            String remark = body.get("remark") == null ? "" : String.valueOf(body.get("remark"));
            return R.ok(reportService.save(r, remark));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("保存失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/reports/{id}")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> delReport(@PathVariable Long id) {
        reportMapper.deleteById(id);
        return R.ok();
    }

    @PostMapping("/reports/{id}/render")
    public R<?> render(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params) {
        ExpandEngine.Result res = reportService.render(id, params);
        return R.ok(toRenderMap(res));
    }

    @PostMapping("/reports/preview")
    public R<?> preview(@RequestBody Map<String, Object> body) {
        ExpandEngine.Result res = reportService.preview(body);
        return R.ok(toRenderMap(res));
    }

    private Map<String, Object> toRenderMap(ExpandEngine.Result res) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", res.rows);
        m.put("cols", res.cols);
        m.put("matrix", res.toMatrix());
        return m;
    }

    @GetMapping("/reports/{id}/versions")
    public R<?> versions(@PathVariable Long id) {
        return R.ok(reportService.versions(id));
    }

    @PostMapping("/reports/{id}/rollback")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> rollback(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.ok(reportService.rollback(id, Integer.parseInt(String.valueOf(body.get("version")))));
    }

    // ---------- M04 参数 ----------
    @GetMapping("/params")
    public R<?> params() {
        return R.ok(paramMapper.selectList(null));
    }

    @PostMapping("/params")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> saveParam(@RequestBody ParamDef p) {
        paramMapper.save(p);
        return R.ok(p);
    }

    @DeleteMapping("/params/{id}")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> delParam(@PathVariable Long id) {
        paramMapper.deleteById(id);
        return R.ok();
    }

    /** 参数联动：根据父参数值返回子参数可选值 (M04-03) */
    @GetMapping("/params/{code}/options")
    public R<?> paramOptions(@PathVariable String code, @RequestParam(required = false) String parentValue) {
        LambdaQueryWrapper<ParamDef> w = new LambdaQueryWrapper<>();
        w.eq(ParamDef::getCode, code);
        ParamDef p = paramMapper.selectOne(w);
        if (p == null) return R.ok(new ArrayList<>());
        if (p.getLinkedParam() != null && !p.getLinkedParam().isEmpty()
                && (parentValue == null || parentValue.isEmpty())) {
            return R.ok(new ArrayList<>());
        }
        // 演示：从 demo_sales 取对应字段去重值（字段白名单防注入）
        String field = "province".equals(code) ? "province" : code;
        if (!field.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return R.ok(new ArrayList<>());
        List<Map<String, Object>> rows = jdbc.query(testDb.driver(), testDb.url(), testDb.username(), testDb.password(),
                "SELECT DISTINCT " + field + " AS v FROM demo_sales ORDER BY v");
        List<String> opts = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r.get("v") != null) opts.add(String.valueOf(r.get("v")));
        }
        return R.ok(opts);
    }

    // ---------- M05 图表 / 仪表板 ----------
    @GetMapping("/charts")
    public R<?> charts() {
        return R.ok(chartMapper.selectList(null));
    }

    @PostMapping("/charts")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> saveChart(@RequestBody Chart c) {
        chartMapper.save(c);
        return R.ok(c);
    }

    @DeleteMapping("/charts/{id}")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> delChart(@PathVariable Long id) {
        chartMapper.deleteById(id);
        return R.ok();
    }

    @PostMapping("/charts/{id}/data")
    public R<?> chartData(@PathVariable Long id) {
        Chart c = chartMapper.selectById(id);
        if (c == null) throw new BizException("图表不存在");
        Map<String, Object> cfg;
        try {
            cfg = om.readValue(c.getConfigJson(), Map.class);
        } catch (Exception e) {
            cfg = new HashMap<>();
        }
        String dim = String.valueOf(cfg.getOrDefault("dim", "region"));
        String metric = String.valueOf(cfg.getOrDefault("metric", "amount"));
        String agg = String.valueOf(cfg.getOrDefault("agg", "SUM"));
        List<Map<String, Object>> rows = datasetQuery.queryById(c.getDatasetId(), new HashMap<>());
        Map<String, Double> grouped = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String k = String.valueOf(r.getOrDefault(dim, ""));
            double v;
            try {
                v = Double.parseDouble(String.valueOf(r.get(metric)));
            } catch (Exception e) {
                v = 0;
            }
            grouped.merge(k, v, (a, b) -> "COUNT".equalsIgnoreCase(agg) ? a + 1 : a + b);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : grouped.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("value", Math.round(e.getValue() * 100) / 100.0);
            out.add(m);
        }
        return R.ok(out);
    }

    @GetMapping("/dashboards")
    public R<?> dashboards() {
        return R.ok(dashboardMapper.selectList(null));
    }

    @PostMapping("/dashboards")
    @RequireRole({"ADMIN", "ANALYST"})
    public R<?> saveDashboard(@RequestBody Dashboard d) {
        dashboardMapper.save(d);
        return R.ok(d);
    }

    // ---------- M06 填报（终端用户可写，故不限制角色，只要求登录） ----------
    @GetMapping("/forms/{id}")
    public R<?> form(@PathVariable Long id) {
        return R.ok(formService.get(id));
    }

    @GetMapping("/forms/{id}/data")
    public R<?> formData(@PathVariable Long id) {
        return R.ok(formService.data(id));
    }

    @PostMapping("/forms/{id}/submit")
    public R<?> formSubmit(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs = body == null ? null : (List<Map<String, Object>>) body.get("records");
        String op = body == null ? "insert" : String.valueOf(body.getOrDefault("op", "insert"));
        return R.ok(formService.submit(id, recs, op));
    }

    // ---------- M07 自助分析 ----------
    @PostMapping("/analysis/run")
    public R<?> analysisRun(@RequestBody Map<String, Object> body) {
        Long dsId = Long.valueOf(String.valueOf(body.get("datasetId")));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = body.get("config") instanceof Map ? (Map<String, Object>) body.get("config") : new HashMap<>();
        return R.ok(analysisService.analyze(dsId, config));
    }

    @PostMapping("/analysis/tosql")
    public R<?> analysisToSql(@RequestBody Map<String, Object> body) {
        Map<String, Object> cfg = body == null ? new HashMap<>() : body;
        Long dsId = null;
        // 兼容两种入参：{datasetId, config:{...}} 与 {datasetId, rows:[...]}
        if (body != null && body.get("config") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inner = (Map<String, Object>) body.get("config");
            cfg = inner;
            if (body.get("datasetId") != null) dsId = Long.valueOf(String.valueOf(body.get("datasetId")));
        } else if (body != null && body.get("datasetId") != null) {
            dsId = Long.valueOf(String.valueOf(body.get("datasetId")));
        }
        return R.ok(analysisService.toSql(dsId, cfg));
    }

    // ---------- 导出 ----------
    /** 报表导出为 Excel（走扩展渲染结果，保留合并单元格与表头样式） */
    @GetMapping("/reports/{id}/export")
    public ResponseEntity<byte[]> exportReport(@PathVariable Long id,
                                               @RequestParam(required = false) Map<String, String> params) {
        Map<String, Object> p = new HashMap<>();
        if (params != null) p.putAll(params);
        p.remove("id");
        byte[] bytes = exportService.reportToXlsx(id, p, watermark());
        return download(bytes, "report-" + id + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /** 报表导出为 PDF（A4 横向，支持中文） */
    @GetMapping("/reports/{id}/export.pdf")
    public ResponseEntity<byte[]> exportReportPdf(@PathVariable Long id,
                                                  @RequestParam(required = false) Map<String, String> params) {
        Map<String, Object> p = new HashMap<>();
        if (params != null) p.putAll(params);
        p.remove("id");
        byte[] bytes = pdfExportService.reportToPdf(id, p, watermark());
        return download(bytes, "report-" + id + ".pdf", "application/pdf");
    }

    /** 数据集导出为 CSV（UTF-8 BOM，Excel 直接打开不乱码） */    @GetMapping("/datasets/{id}/export.csv")
    public ResponseEntity<byte[]> exportDataset(@PathVariable Long id,
                                               @RequestParam(required = false) Map<String, String> params) {
        Map<String, Object> p = new HashMap<>();
        if (params != null) p.putAll(params);
        p.remove("id");
        List<Map<String, Object>> rows = datasetQuery.queryById(id, p);
        byte[] bytes = exportService.datasetToCsv(rows, watermark());
        return download(bytes, "dataset-" + id + ".csv", "text/csv;charset=UTF-8");
    }

    /** 导出水印：谁在什么时候导出的，用于防外传追溯 */
    private String watermark() {
        String user = com.rbi.security.AuthContext.usernameOrSystem();
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
        return "导出人 " + user + " · " + time + " · 仅限内部使用";
    }

    private ResponseEntity<byte[]> download(byte[] bytes, String fileName, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileName, java.nio.charset.StandardCharsets.UTF_8).build());
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    // ---------- 演示数据直通 ----------
    @GetMapping("/demo/sales")
    public R<?> demoSales() {
        return R.ok(jdbc.query(testDb.driver(), testDb.url(), testDb.username(), testDb.password(),
                "SELECT * FROM demo_sales ORDER BY id LIMIT 200"));
    }
}
