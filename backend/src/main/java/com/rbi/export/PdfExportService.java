package com.rbi.export;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.rbi.engine.ExpandEngine;
import com.rbi.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * 报表 PDF 导出：A4 横向，保留合并单元格与基础样式，支持中文。
 * 字体策略：优先系统中文 TTF（Windows/Linux 常见路径），退化到 openpdf-fonts-extra 的 STSong-Light。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExportService {

    private final ReportService reportService;

    /** 系统中文 TTF 候选路径（部署机有则优先，字形更好） */
    private static final String[] SYSTEM_FONTS = {
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/simhei.ttf"
    };

    public byte[] reportToPdf(Long reportId, Map<String, Object> params, String watermark) {
        ExpandEngine.Result result = reportService.render(reportId, params);
        List<List<Map<String, Object>>> matrix = result.toMatrix();

        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 28, 32);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            if (watermark != null && !watermark.trim().isEmpty()) {
                writer.setPageEvent(new WatermarkEvent(watermark, baseFont()));
            }
            doc.open();

            int cols = Math.max(result.cols, 1);
            PdfPTable table = new PdfPTable(cols);
            table.setWidthPercentage(100);
            table.setHeaderRows(0);

            int rows = matrix.size();
            boolean[][] skip = new boolean[rows][cols];
            BaseFont bf = baseFont();

            for (int r = 0; r < rows; r++) {
                List<Map<String, Object>> line = matrix.get(r);
                for (int c = 0; c < cols; c++) {
                    if (skip[r][c]) continue;
                    Map<String, Object> cell = c < line.size() ? line.get(c) : null;
                    int colspan = cell == null ? 1 : toInt(cell.get("colspan"), 1);
                    int rowspan = cell == null ? 1 : toInt(cell.get("rowspan"), 1);
                    // 兼容历史布局把 colspan/rowspan 写在 style 内
                    if (cell != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> st = (Map<String, Object>) cell.get("style");
                        if (st != null) {
                            if (colspan <= 1) colspan = toInt(st.get("colspan"), 1);
                            if (rowspan <= 1) rowspan = toInt(st.get("rowspan"), 1);
                        }
                    }
                    colspan = Math.min(colspan, cols - c);
                    rowspan = Math.min(rowspan, rows - r);

                    PdfPCell pc = buildCell(cell, bf, rowspan, colspan);
                    table.addCell(pc);

                    for (int i = r; i < r + rowspan; i++)
                        for (int j = c; j < c + colspan; j++)
                            if (i != r || j != c) skip[i][j] = true;
                }
            }
            doc.add(table);

            if (watermark != null && !watermark.trim().isEmpty()) {
                Paragraph p = new Paragraph(watermark, new Font(bf, 8, Font.ITALIC, Color.GRAY));
                p.setSpacingBefore(8);
                doc.add(p);
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("[导出] PDF 导出失败", e);
            throw new RuntimeException("PDF 导出失败: " + e.getMessage());
        } finally {
            if (doc.isOpen()) doc.close();
        }
    }

    private PdfPCell buildCell(Map<String, Object> cell, BaseFont bf, int rowspan, int colspan) {
        Object v = cell == null ? null : cell.get("value");
        String text = v == null ? "" : String.valueOf(v);

        Font font = new Font(bf, 9);
        int align = Element.ALIGN_LEFT;
        Color bg = null;

        if (cell != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> style = (Map<String, Object>) cell.get("style");
            if (style != null) {
                int size = toInt(style.get("fontSize"), 9);
                boolean bold = isTrue(style.get("bold"));
                font = new Font(bf, Math.max(7, Math.min(size, 18)), bold ? Font.BOLD : Font.NORMAL);
                String a = String.valueOf(style.getOrDefault("align", "left"));
                if ("center".equalsIgnoreCase(a)) align = Element.ALIGN_CENTER;
                else if ("right".equalsIgnoreCase(a)) align = Element.ALIGN_RIGHT;
                Integer rgb = parseRgb(style.get("bg"));
                if (rgb != null) bg = new Color(rgb);
            }
        }

        PdfPCell pc = new PdfPCell(new Phrase(text, font));
        pc.setHorizontalAlignment(align);
        pc.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pc.setPadding(4f);
        pc.setRowspan(rowspan);
        pc.setColspan(colspan);
        pc.setMinimumHeight(18f);
        if (bg != null) pc.setBackgroundColor(bg);
        return pc;
    }

    /** 中文字体：系统 TTF 优先，退化到 openpdf-fonts-extra 的 STSong-Light */
    private BaseFont baseFont() {
        for (String path : SYSTEM_FONTS) {
            try {
                java.io.File f = new java.io.File(path);
                if (f.exists()) return BaseFont.createFont(path + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignore) { /* 尝试下一个 */ }
        }
        try {
            return BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            log.warn("[导出] 中文字体加载失败，PDF 中文可能缺字: {}", e.getMessage());
            try {
                return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception ex) {
                throw new RuntimeException("PDF 字体初始化失败", ex);
            }
        }
    }

    /** 页脚水印事件 */
    private static class WatermarkEvent extends PdfPageEventHelper {
        private final String text;
        private final BaseFont bf;
        WatermarkEvent(String text, BaseFont bf) { this.text = text; this.bf = bf; }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            cb.saveState();
            cb.setColorFill(new Color(150, 150, 150));
            cb.beginText();
            try {
                cb.setFontAndSize(bf, 8);
                cb.showTextAligned(Element.ALIGN_CENTER, text,
                        (document.left() + document.right()) / 2, document.bottom() - 14, 0);
            } catch (Exception ignore) {}
            cb.endText();
            cb.restoreState();
        }
    }

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
