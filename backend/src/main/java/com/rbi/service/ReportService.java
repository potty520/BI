package com.rbi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbi.common.BizException;
import com.rbi.engine.ExpandEngine;
import com.rbi.engine.LayoutModel;
import com.rbi.entity.Report;
import com.rbi.entity.ReportVersion;
import com.rbi.mapper.ReportMapper;
import com.rbi.mapper.ReportVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 报表服务 (M03)：模板存取、版本、扩展渲染
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {
    private final ReportMapper reportMapper;
    private final ReportVersionMapper versionMapper;
    private final DatasetQueryService datasetQuery;
    private final ObjectMapper om = new ObjectMapper();

    /** 渲染报表：加载布局 -> 查数据 -> 扩展引擎 -> 网格 */
    public ExpandEngine.Result render(Long reportId, Map<String,Object> params) {
        Report r = reportMapper.selectById(reportId);
        if (r == null) throw new BizException("报表不存在: " + reportId);
        LayoutModel layout = parseLayout(r.getLayoutJson());
        List<Map<String,Object>> data = r.getDatasetId()==null
                ? new ArrayList<>() : datasetQuery.queryById(r.getDatasetId(), params);
        return new ExpandEngine().expand(layout, data);
    }

    /** 直接用传入布局渲染（设计器实时预览，无需先保存） */
    public ExpandEngine.Result preview(Map<String,Object> body) {
        try {
            LayoutModel layout = om.readValue(om.writeValueAsString(body.get("layout")), LayoutModel.class);
            Long dsId = body.get("datasetId") == null ? null : Long.valueOf(String.valueOf(body.get("datasetId")));
            @SuppressWarnings("unchecked")
            Map<String,Object> params = body.get("params") instanceof Map
                    ? (Map<String,Object>) body.get("params") : new HashMap<>();
            List<Map<String,Object>> data = dsId==null ? new ArrayList<>() : datasetQuery.queryById(dsId, params);
            return new ExpandEngine().expand(layout, data);
        } catch (BizException e) { throw e;
        } catch (Exception e) { throw new BizException("预览失败: " + e.getMessage()); }
    }

    /** 保存模板并生成版本 (M03-06) */
    @Transactional
    public Report save(Report report, String remark) {
        if (report.getId() == null) {
            report.setCurrentVersion(1);
            reportMapper.insert(report);
            saveVersion(report.getId(), 1, report.getLayoutJson(), remark);
        } else {
            Report old = reportMapper.selectById(report.getId());
            int v = (old==null?0:old.getCurrentVersion()==null?0:old.getCurrentVersion()) + 1;
            report.setCurrentVersion(v);
            reportMapper.updateById(report);
            saveVersion(report.getId(), v, report.getLayoutJson(), remark);
        }
        return report;
    }

    private void saveVersion(Long reportId, int version, String layout, String remark) {
        ReportVersion rv = new ReportVersion();
        rv.setReportId(reportId); rv.setVersion(version); rv.setLayoutJson(layout);
        rv.setRemark(remark == null ? "自动保存" : remark); rv.setCreateBy("admin");
        rv.setCreateTime(new Date());
        versionMapper.insert(rv);
    }

    public List<ReportVersion> versions(Long reportId) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReportVersion> w =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        w.eq("report_id", reportId).orderByDesc("version");
        return versionMapper.selectList(w);
    }

    /** 回滚到指定版本 */
    @Transactional
    public Report rollback(Long reportId, int version) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReportVersion> w =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        w.eq("report_id", reportId).eq("version", version);
        ReportVersion rv = versionMapper.selectOne(w);
        if (rv == null) throw new BizException("版本不存在");
        Report r = reportMapper.selectById(reportId);
        r.setLayoutJson(rv.getLayoutJson());
        r.setCurrentVersion(version);
        reportMapper.updateById(r);
        return r;
    }

    private LayoutModel parseLayout(String json) {
        try {
            if (json == null || json.trim().isEmpty()) return new LayoutModel();
            return om.readValue(json, LayoutModel.class);
        } catch (Exception e) { throw new BizException("布局解析失败: " + e.getMessage()); }
    }

    /** 带数据集名的列表 */
    public List<Map<String,Object>> listWithDatasetName() {
        List<Report> reports = reportMapper.selectList(null);
        List<Map<String,Object>> out = new ArrayList<>();
        for (Report r : reports) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", r.getId()); m.put("name", r.getName());
            m.put("directoryId", r.getDirectoryId()); m.put("datasetId", r.getDatasetId());
            m.put("status", r.getStatus()); m.put("currentVersion", r.getCurrentVersion());
            m.put("updateTime", r.getUpdateTime());
            out.add(m);
        }
        return out;
    }
}
