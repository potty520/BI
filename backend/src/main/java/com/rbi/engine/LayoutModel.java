package com.rbi.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表布局模型 = 单元格集合 + 网格尺寸
 */
public class LayoutModel {
    private int cols = 10;
    private int rows = 20;
    private List<CellModel> cells = new ArrayList<>();
    /** 分页：每页重复的表头行数（0 表示不重复） */
    private int headerRows = 0;
    /** 每页最大数据行数（0 表示按纸张自动分页） */
    private int pageSize = 0;
    /** 页脚文字，支持占位符 {page} {pages} */
    private String footerText = "";
    /** 纸张：A4 / A3 / LETTER */
    private String pageSizeName = "A4";
    /** 按该字段分组换页（字段值变化时重新起页），空表示不分组分页 */
    private String breakOn = "";

    public String getBreakOn() { return breakOn == null ? "" : breakOn; }
    public void setBreakOn(String breakOn) { this.breakOn = breakOn; }

    public int getCols() { return cols; }
    public void setCols(int cols) { this.cols = cols; }
    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }
    public List<CellModel> getCells() { return cells == null ? (cells = new ArrayList<>()) : cells; }
    public void setCells(List<CellModel> cells) { this.cells = cells; }

    public int getHeaderRows() { return headerRows; }
    public void setHeaderRows(int headerRows) { this.headerRows = headerRows; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public String getFooterText() { return footerText == null ? "" : footerText; }
    public void setFooterText(String footerText) { this.footerText = footerText; }
    public String getPageSizeName() { return pageSizeName == null ? "A4" : pageSizeName; }
    public void setPageSizeName(String pageSizeName) { this.pageSizeName = pageSizeName; }

    public CellModel cell(String ref) {
        for (CellModel c : cells) {
            if (ref.equalsIgnoreCase(c.getRef())) return c;
        }
        return null;
    }
}
