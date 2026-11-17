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

    public int getCols() { return cols; }
    public void setCols(int cols) { this.cols = cols; }
    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }
    public List<CellModel> getCells() { return cells == null ? (cells = new ArrayList<>()) : cells; }
    public void setCells(List<CellModel> cells) { this.cells = cells; }

    public CellModel cell(String ref) {
        for (CellModel c : cells) {
            if (ref.equalsIgnoreCase(c.getRef())) return c;
        }
        return null;
    }
}
