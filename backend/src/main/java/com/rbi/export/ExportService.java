package com.rbi.export;

import com.rbi.engine.ExpandEngine;
import com.rbi.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 报表导出服务：Excel（保留扩展渲染的合并单元格与样式）与 CSV（UTF-8 BOM）。
 * 导出内容统一带水印，便于外传追溯。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ReportService reportService;

    /** 兼容两参调用 */
    public byte[] reportToXlsx(Long reportId, Map<String, Object> params) {
        return reportToXlsx(reportId, params, null);
    }

    /** 报表导出为 Excel：走扩展引擎渲染结果，还原合并区域、字体/底色/对齐/数字格式 */
    public byte[] reportToXlsx(Long reportId, Map<String, Object> params, String watermark) {
        ExpandEngine.Result result = reportService.render(reportId, params);
        List<List<Map<String, Object>>> matrix = result.toMatrix();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("报表");
            Map<String, CellStyle> styleCache = new HashMap<>();

            for (int r = 0; r < matrix.size(); r++) {
                List<Map<String, Object>> line = matrix.get(r);
                Row row = sheet.createRow(r);
                row.setHeightInPoints(20f);
                for (int c = 0; c < line.size(); c++) {
                    Map<String, Object> cell = line.get(c);
                    Cell xc = row.createCell(c);
                    if (cell == null) continue;
                    writeValue(xc, cell.get("value"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> style = (Map<String, Object>) cell.get("style");
                    xc.setCellStyle(styleOf(wb, styleCache, style));

                    // colspan/rowspan：优先取单元格属性，兼容历史布局把其写在 style 内的情况
                    int colspan = toInt(cell.get("colspan"), 1);
                    int rowspan = toInt(cell.get("rowspan"), 1);
                    if (colspan <= 1 && style != null) colspan = toInt(style.get("colspan"), 1);
                    if (rowspan <= 1 && style != null) rowspan = toInt(style.get("rowspan"), 1);
                    if (colspan > 1 || rowspan > 1) {
                        try {
                            sheet.addMergedRegion(new CellRangeAddress(r, r + rowspan - 1, c, c + colspan - 1));
                        } catch (Exception ignore) { /* 重叠区域忽略 */ }
                    }
                }
            }
            for (int c = 0; c < Math.max(result.cols, 1); c++) sheet.setColumnWidth(c, 14 * 256);

            if (watermark != null && !watermark.trim().isEmpty()) {
                sheet.getFooter().setCenter(watermark);
                Row foot = sheet.createRow(matrix.size() + 1);
                Cell fc = foot.createCell(0);
                fc.setCellValue(watermark);
                Font italic = wb.createFont();
                italic.setItalic(true);
                italic.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
                CellStyle fs = wb.createCellStyle();
                fs.setFont(italic);
                fc.setCellStyle(fs);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("[导出] Excel 导出失败", e);
            throw new RuntimeException("Excel 导出失败: " + e.getMessage());
        }
    }

    /** 数据集导出为 CSV（UTF-8 BOM，Excel 直接打开不乱码） */
    public byte[] datasetToCsv(List<Map<String, Object>> rows, String watermark) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        if (rows == null) rows = new ArrayList<>();
        if (!rows.isEmpty()) {
            LinkedHashSet<String> headers = new LinkedHashSet<>();
            for (Map<String, Object> r : rows) headers.addAll(r.keySet());
            sb.append(joinCsv(new ArrayList<>(headers))).append("\r\n");
            for (Map<String, Object> r : rows) {
                List<String> vals = new ArrayList<>();
                for (String h : headers) vals.add(csvCell(r.get(h)));
                sb.append(joinCsv(vals)).append("\r\n");
            }
        }
        if (watermark != null && !watermark.trim().isEmpty()) {
            sb.append("\r\n# ").append(csvCell(watermark)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String joinCsv(List<String> vals) {
        StringJoiner j = new StringJoiner(",");
        for (String v : vals) j.add(v);
        return j.toString();
    }

    private String csvCell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private void writeValue(Cell xc, Object v) {
        if (v == null) { xc.setBlank(); return; }
        if (v instanceof Number) xc.setCellValue(((Number) v).doubleValue());
        else if (v instanceof Boolean) xc.setCellValue((Boolean) v);
        else xc.setCellValue(String.valueOf(v));
    }

    /** 样式映射：复用 ExpandEngine 产出的 style 结构（bold/fontSize/align/bg/color/format） */
    private CellStyle styleOf(XSSFWorkbook wb, Map<String, CellStyle> cache, Map<String, Object> style) {
        String key = String.valueOf(style == null ? "{}" : style.hashCode());
        if (cache.containsKey(key)) return cache.get(key);

        XSSFCellStyle cs = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontName("微软雅黑");
        f.setFontHeightInPoints((short) toInt(style == null ? null : style.get("fontSize"), 11));
        if (style != null && isTrue(style.get("bold"))) f.setBold(true);
        cs.setFont(f);
        cs.setVerticalAlignment(VerticalAlignment.CENTER);

        if (style != null) {
            String align = String.valueOf(style.getOrDefault("align", "left"));
            if ("center".equalsIgnoreCase(align)) cs.setAlignment(HorizontalAlignment.CENTER);
            else if ("right".equalsIgnoreCase(align)) cs.setAlignment(HorizontalAlignment.RIGHT);
            else cs.setAlignment(HorizontalAlignment.LEFT);

            Integer rgb = parseRgb(style.get("bg"));
            if (rgb != null) {
                cs.setFillForegroundColor(new XSSFColor(
                        new java.awt.Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF), null));
                cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            Object fmt = style.get("format");
            if (fmt != null) {
                try { cs.setDataFormat(wb.createDataFormat().getFormat(String.valueOf(fmt))); } catch (Exception ignore) {}
            }
        }
        cs.setBorderBottom(BorderStyle.THIN);
        cs.setBorderTop(BorderStyle.THIN);
        cs.setBorderLeft(BorderStyle.THIN);
        cs.setBorderRight(BorderStyle.THIN);
        cache.put(key, cs);
        return cs;
    }

    /** 解析 #RRGGBB / RRGGBB 为 int；无法解析返回 null */
    private Integer parseRgb(Object o) {
        if (o == null) return null;
        String hex = String.valueOf(o).trim().replace("#", "");
        if (hex.length() < 6) return null;
        try { return Integer.parseInt(hex.substring(0, 6), 16); } catch (Exception e) { return null; }
    }

    private boolean isTrue(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }

    private int toInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return o == null ? def : Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }
}
