package com.rbi.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.common.R;
import com.rbi.config.DataSourceProvider;
import com.rbi.datasource.JdbcExecutor;
import com.rbi.datasource.SqlSafeBuilder;
import com.rbi.engine.ExpandEngine;
import com.rbi.entity.*;
import com.rbi.mapper.*;
import com.rbi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 核心业务控制器：数据源 / 数据集 / 报表 / 参数 / 分析 / 填报 / 图表
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
    private final ChartMapper chartMapper;
    private final DashboardMapper dashboardMapper;
    private final ObjectMapper om = new ObjectMapper();

    // ---------- M01 数据源 ----------
    @GetMapping("/datasources")
    public R<?> datasources() { return R.ok(dsMapper.selectList(null)); }

    @PostMapping("/datasources")
    public R<?> saveDatasource(@RequestBody Datasource d) { dsMapper.save(d); return R.ok(d); }

    @DeleteMapping("/datasources/{id}")
    public R<?> delDatasource(@PathVariable Long id) { dsMapper.deleteById(id); return R.ok(); }

    @PostMapping("/datasources/test")
    public R<?> testDatasource(@RequestBody Datasource d) {
        jdbc.testConnection(d.getDriverClass(), d.getJdbcUrl(), d.getUsername(), d.getPasswordEnc());
        return R.ok("连接成功");
    }

    @GetMapping("/datasource/drivers")
    public R<?> drivers() { return R.ok(JdbcExecutor.DRIVERS); }

    // ---------- M02 数据集 ----------
    @GetMapping("/datasets")
    public R<?> datasets() { return R.ok(datasetMapper.selectList(null)); }

    @PostMapping("/datasets")
    public R<?> saveDataset(@RequestBody Dataset d) {
        if (d.getId()==null) datasetMapper.insert(d); else datasetMapper.updateById(d);
        datasetQuery.clearCache(d.getId());
        return R.ok(d);
    }

    @DeleteMapping("/datasets/{id}")
    public R<?> delDataset(@PathVariable Long id) { datasetMapper.deleteById(id); return R.ok(); }

    @GetMapping("/datasets/{id}")
    public R<?> getDataset(@PathVariable Long id) { return R.ok(datasetMapper.selectById(id)); }

    @PostMapping("/datasets/{id}/query")
    public R<?> queryDataset(@PathVariable Long id, @RequestBody(required=false) Map<String,Object> params) {
        return R.ok(datasetQuery.queryById(id, params));
    }

    @GetMapping("/datasets/{id}/fields")
    public R<?> datasetFields(@PathVariable Long id) { return R.ok(datasetQuery.fields(id)); }

    @PostMapping("/sql/execute")
    public R<?> executeSql(@RequestBody Map<String,Object> body) {
        Dataset d = new Dataset();
        d.setId(-1L);
        d.setType("sql");
        d.setSqlText(String.valueOf(body.get("sql")));
        @SuppressWarnings("unchecked")
        Map<String,Object> p = body.get("params") instanceof Map ? (Map<String,Object>)body.get("params") : new HashMap<>();
        return R.ok(datasetQuery.query(d, p));
    }

    // ---------- M03 报表 ----------
    @GetMapping("/reports")
    public R<?> reports() { return R.ok(reportService.listWithDatasetName()); }

    @GetMapping("/reports/{id}")
    public R<?> getReport(@PathVariable Long id) {
        return R.ok(reportMapper.selectById(id));
    }

    @PostMapping("/reports")
    public R<?> saveReport(@RequestBody Map<String,Object> body) {
        try {
            Report r = om.readValue(om.writeValueAsString(body.get("report")), Report.class);
            String remark = body.get("remark")==null?"":String.valueOf(body.get("remark"));
            return R.ok(reportService.save(r, remark));
        } catch (Exception e) { throw new BizException("保存失败: "+e.getMessage()); }
    }

    @DeleteMapping("/reports/{id}")
    public R<?> delReport(@PathVariable Long id) { reportMapper.deleteById(id); return R.ok(); }

    @PostMapping("/reports/{id}/render")
    public R<?> render(@PathVariable Long id, @RequestBody(required=false) Map<String,Object> params) {
        ExpandEngine.Result res = reportService.render(id, params);
        return R.ok(toRenderMap(res));
    }

    @PostMapping("/reports/preview")
    public R<?> preview(@RequestBody Map<String,Object> body) {
        ExpandEngine.Result res = reportService.preview(body);
        return R.ok(toRenderMap(res));
    }

    private Map<String,Object> toRenderMap(ExpandEngine.Result res) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("rows", res.rows);
        m.put("cols", res.cols);
        m.put("matrix", res.toMatrix());
        return m;
    }

    @GetMapping("/reports/{id}/versions")
    public R<?> versions(@PathVariable Long id) { return R.ok(reportService.versions(id)); }

    @PostMapping("/reports/{id}/rollback")
    public R<?> rollback(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        return R.ok(reportService.rollback(id, Integer.parseInt(String.valueOf(body.get("version")))));
    }

    // ---------- M04 参数 ----------
    @GetMapping("/params")
    public R<?> params() { return R.ok(paramMapper.selectList(null)); }

    @PostMapping("/params")
    public R<?> saveParam(@RequestBody ParamDef p) { paramMapper.save(p); return R.ok(p); }

    @DeleteMapping("/params/{id}")
    public R<?> delParam(@PathVariable Long id) { paramMapper.deleteById(id); return R.ok(); }

    /** 参数联动：根据父参数值返回子参数可选值 (M04-03) */
    @GetMapping("/params/{code}/options")
    public R<?> paramOptions(@PathVariable String code, @RequestParam(required=false) String parentValue) {
        LambdaQueryWrapper<ParamDef> w = new LambdaQueryWrapper<>();
        w.eq(ParamDef::getCode, code);
        ParamDef p = paramMapper.selectOne(w);
        if (p == null) return R.ok(new ArrayList<>());
        if (p.getLinkedParam()!=null && !p.getLinkedParam().isEmpty() && (parentValue==null || parentValue.isEmpty()))
            return R.ok(new ArrayList<>());
        // 演示：从 demo_sales 取对应字段去重值（字段白名单防注入）
        String field = code.equals("province") ? "province" : code;
        if (!field.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return R.ok(new ArrayList<>());
        List<Map<String,Object>> rows = jdbc.query(testDb.driver(), testDb.url(), testDb.username(), testDb.password(),
                "SELECT DISTINCT " + field + " AS v FROM demo_sales ORDER BY v");
        List<String> opts = new ArrayList<>();
        for (Map<String,Object> r : rows) if (r.get("v")!=null) opts.add(String.valueOf(r.get("v")));
        return R.ok(opts);
    }

    // ---------- M05 图表 / 仪表板 ----------
    @GetMapping("/charts")
    public R<?> charts() { return R.ok(chartMapper.selectList(null)); }

    @PostMapping("/charts")
    public R<?> saveChart(@RequestBody Chart c) { chartMapper.save(c); return R.ok(c); }

    @DeleteMapping("/charts/{id}")
    public R<?> delChart(@PathVariable Long id) { chartMapper.deleteById(id); return R.ok(); }

    @PostMapping("/charts/{id}/data")
    public R<?> chartData(@PathVariable Long id) {
        Chart c = chartMapper.selectById(id);
        if (c==null) throw new BizException("图表不存在");
        Map<String,Object> cfg;
        try { cfg = om.readValue(c.getConfigJson(), Map.class); }
        catch (Exception e) { cfg = new HashMap<>(); }
        String dim = String.valueOf(cfg.getOrDefault("dim","region"));
        String metric = String.valueOf(cfg.getOrDefault("metric","amount"));
        String agg = String.valueOf(cfg.getOrDefault("agg","SUM"));
        List<Map<String,Object>> rows = datasetQuery.queryById(c.getDatasetId(), new HashMap<>());
        Map<String,Double> grouped = new LinkedHashMap<>();
        for (Map<String,Object> r : rows) {
            String k = String.valueOf(r.getOrDefault(dim,""));
            double v;
            try { v = Double.parseDouble(String.valueOf(r.get(metric))); } catch (Exception e) { v = 0; }
            grouped.merge(k, v, (a,b)-> agg.equalsIgnoreCase("COUNT") ? a+1 : a+b);
        }
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map.Entry<String,Double> e : grouped.entrySet()) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("value", Math.round(e.getValue()*100)/100.0);
            out.add(m);
        }
        return R.ok(out);
    }

    @GetMapping("/dashboards")
    public R<?> dashboards() { return R.ok(dashboardMapper.selectList(null)); }

    @PostMapping("/dashboards")
    public R<?> saveDashboard(@RequestBody Dashboard d) { dashboardMapper.save(d); return R.ok(d); }

    // ---------- M06 填报 ----------
    @GetMapping("/forms/{id}")
    public R<?> form(@PathVariable Long id) { return R.ok(formService.get(id)); }

    @GetMapping("/forms/{id}/data")
    public R<?> formData(@PathVariable Long id) { return R.ok(formService.data(id)); }

    @PostMapping("/forms/{id}/submit")
    public R<?> formSubmit(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> recs = (List<Map<String,Object>>) body.get("records");
        return R.ok(formService.submit(id, recs, String.valueOf(body.getOrDefault("op","insert"))));
    }

    // ---------- M07 自助分析 ----------
    @PostMapping("/analysis/run")
    public R<?> analysisRun(@RequestBody Map<String,Object> body) {
        Long dsId = Long.valueOf(String.valueOf(body.get("datasetId")));
        @SuppressWarnings("unchecked")
        Map<String,Object> config = body.get("config") instanceof Map ? (Map<String,Object>)body.get("config") : new HashMap<>();
        return R.ok(analysisService.analyze(dsId, config));
    }

    @PostMapping("/analysis/tosql")
    public R<?> analysisToSql(@RequestBody Map<String,Object> config) { return R.ok(analysisService.toSql(config)); }

    // ---------- 演示数据直通 ----------
    @GetMapping("/demo/sales")
    public R<?> demoSales() {
        return R.ok(jdbc.query(testDb.driver(), testDb.url(), testDb.username(), testDb.password(),
                "SELECT * FROM demo_sales ORDER BY id LIMIT 200"));
    }
}
