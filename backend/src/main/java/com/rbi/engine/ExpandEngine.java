package com.rbi.engine;

import java.util.*;

/**
 * 单元格扩展引擎 —— 中国式复杂报表的技术核心 (M03-02)
 *
 * 核心模型（区域继承）：
 *  1. 单元格类型：text 文本 / dataset 数据字段 / formula 公式。
 *  2. 数据单元格设置扩展方向 expand：v 纵向、h 横向、none 不扩展。
 *  3. 单元格可设 parent 父格：父格的每个取值形成一个"数据分组上下文"，
 *     子格在该上下文内继续展开，实现 大区→省份→类别 的逐层下钻。
 *  4. 关键：父格纵向展开后，若子格继续纵向展开会"撑开"父格——父格的值在其
 *     覆盖的行区间内重复填充，子格占据这些行。这是与朴素实现最大的区别。
 *  5. 公式格在父格上下文中聚合求值（只统计该分组的明细行）。
 *
 * 算法：先用 measureRows 自底向上预判每个格在给定数据上需要的行数，再自顶向下
 *       递归分配坐标并渲染，保证父子格行对齐。
 */
public class ExpandEngine {

    /** 渲染结果单元格 */
    public static class RenderCell {
        public int row, col;
        public Object value;
        public CellModel source;
        public Map<String,Object> style = new LinkedHashMap<>();
        public int colspan = 1, rowspan = 1;
        public Map<String,Object> rowData;

        public RenderCell(int row, int col, Object value, CellModel source) {
            this.row=row; this.col=col; this.value=value; this.source=source;
        }
    }

    public static class Result {
        public int rows, cols;
        public Map<String,RenderCell> cells = new LinkedHashMap<>();
        public RenderCell at(int row, int col) { return cells.get(row+"_"+col); }

        public List<List<Map<String,Object>>> toMatrix() {
            List<List<Map<String,Object>>> matrix = new ArrayList<>();
            for (int r=0;r<rows;r++) {
                List<Map<String,Object>> line = new ArrayList<>();
                for (int c=0;c<cols;c++) {
                    RenderCell rc = at(r,c);
                    if (rc == null) { line.add(null); continue; }
                    Map<String,Object> m = new LinkedHashMap<>();
                    m.put("ref", CellModel.toRef(c+1,r+1));
                    m.put("value", rc.value);
                    m.put("style", rc.style);
                    m.put("type", rc.source==null?"text":rc.source.getType());
                    m.put("colspan", rc.colspan);
                    m.put("rowspan", rc.rowspan);
                    if (rc.source!=null) { m.put("field", rc.source.getField()); m.put("srcRef", rc.source.getRef()); }
                    line.add(m);
                }
                matrix.add(line);
            }
            return matrix;
        }
    }

    /** 单元格展开实例 */
    private static class CellInstance {
        int row, col, rowSpan=1, colSpan=1;
        Object value;
        List<Map<String,Object>> data;
        CellInstance(int row, int col, Object value, List<Map<String,Object>> data) {
            this.row=row; this.col=col; this.value=value; this.data=data;
        }
    }

    private Map<String,List<CellModel>> childrenMap;
    private Map<String,CellModel> byRef;
    private Result result;
    private List<Map<String,Object>> allRows;

    public Result expand(LayoutModel layout, List<Map<String,Object>> dataSet) {
        result = new Result();
        allRows = dataSet == null ? new ArrayList<>() : dataSet;
        if (layout == null || layout.getCells().isEmpty()) { result.rows=1; result.cols=1; return result; }

        byRef = new HashMap<>();
        for (CellModel c : layout.getCells()) byRef.put(c.getRef().toUpperCase(), c);
        childrenMap = new HashMap<>();
        for (CellModel c : layout.getCells()) {
            String p = c.getParent()==null?"":c.getParent().trim().toUpperCase();
            if (!p.isEmpty() && byRef.containsKey(p)) childrenMap.computeIfAbsent(p,k->new ArrayList<>()).add(c);
        }

        int maxR=0, maxC=0;
        // 展开所有"根格"（无有效父格）
        for (CellModel c : layout.getCells()) {
            String p = c.getParent()==null?"":c.getParent().trim().toUpperCase();
            if (p.isEmpty() || !byRef.containsKey(p) || p.equals(c.getRef().toUpperCase())) {
                int[] rc = CellModel.parse(c.getRef());
                int r = Math.max(0, rc[1]-1), col = Math.max(0, rc[0]-1);
                expandNode(c, r, col, allRows, null);
            }
            int[] rc = CellModel.parse(c.getRef());
            maxR = Math.max(maxR, rc[1]); maxC = Math.max(maxC, rc[0]);
        }
        // 网格尺寸：实际渲染范围 与 设计预留 取较大
        for (RenderCell rc : result.cells.values()) { maxR=Math.max(maxR,rc.row+1); maxC=Math.max(maxC,rc.col+1); }
        result.rows = Math.min(Math.max(maxR,1), 300);
        result.cols = Math.min(Math.max(maxC,1), 80);
        return result;
    }

    /** 递归展开节点：在 (row,col) 起点、数据 data 上下文内展开，parentInst 为父实例 */
    private void expandNode(CellModel cell, int row, int col, List<Map<String,Object>> data, CellInstance parentInst) {
        String ref = cell.getRef().toUpperCase();
        List<CellModel> children = childrenMap.getOrDefault(ref, new ArrayList<>());
        String type = cell.getType()==null?"text":cell.getType();
        String expand = cell.getExpand()==null?"none":cell.getExpand().toLowerCase();

        if ("dataset".equals(type) && "v".equals(expand)) {
            // 纵向扩展：字段去重值向下铺开，每个值被其子格撑开若干行
            List<Object> values = distinct(data, cell.getField());
            if (values.isEmpty()) values = Collections.singletonList("");
            int r = row;
            int vidx = 0;
            for (Object v : values) {
                List<Map<String,Object>> subset = filterByField(data, cell.getField(), v);
                int span = 1;
                for (CellModel ch : children) span = Math.max(span, measureRows(ch, subset));
                // 父格值在整段行区间重复填充（纵向继承）
                for (int k=0;k<span;k++) put(r+k, col, v, cell, subset);
                CellInstance inst = new CellInstance(r, col, v, subset);
                inst.rowSpan = span;
                for (CellModel ch : children) {
                    String ct = ch.getType()==null?"text":ch.getType();
                    String ce = ch.getExpand()==null?"none":ch.getExpand().toLowerCase();
                    // 非"纵向扩展数据格"的子格（公式/文本/不扩展格）只在首个父分组求值一次，
                    // 否则会在每个父格值上重复渲染到同一坐标并被最后一个覆盖
                    if ("dataset".equals(ct) && "v".equals(ce)) {
                        int childCol = col + colOffset(cell, ch);
                        expandNode(ch, r, childCol, subset, inst);
                    } else if (vidx == 0) {
                        int childCol = col + colOffset(cell, ch);
                        expandNode(ch, r, childCol, subset, inst);
                    }
                }
                r += span;
                vidx++;
            }
        } else if ("dataset".equals(type) && "h".equals(expand)) {
            // 横向扩展：字段去重值向右铺开
            List<Object> values = distinct(data, cell.getField());
            if (values.isEmpty()) values = Collections.singletonList("");
            int c = col;
            for (Object v : values) {
                List<Map<String,Object>> subset = filterByField(data, cell.getField(), v);
                put(row, c, v, cell, subset);
                for (CellModel ch : children) {
                    int childRow = row + rowOffset(cell, ch);
                    expandNode(ch, childRow, c, subset, null);
                }
                c++;
            }
        } else if ("formula".equals(type)) {
            // 公式在父格上下文中求值
            List<Map<String,Object>> ctxRows = parentInst==null ? data : parentInst.data;
            FormulaEngine.Context fctx = new FormulaEngine.Context().rows(ctxRows);
            // 注入同上下文中已展开的单元格值，支持 A1+B1
            for (Map.Entry<String,RenderCell> e : result.cells.entrySet()) {
                RenderCell rc = e.getValue();
                if (rc.source!=null && rc.value instanceof Number)
                    fctx.cell(CellModel.toRef(rc.col+1,rc.row+1), rc.value);
            }
            Object value = FormulaEngine.eval(cell.getValue(), fctx);
            put(row, col, value, cell, ctxRows);
            for (CellModel ch : children) expandNode(ch, row+rowOffset(cell,ch), col+colOffset(cell,ch), ctxRows, null);
        } else {
            // 文本 / 不扩展数据格
            Object v = "dataset".equals(type)
                    ? (data.isEmpty()?"":data.get(0).get(cell.getField()))
                    : cell.getValue();
            put(row, col, v, cell, data);
            for (CellModel ch : children) {
                int span = measureRows(ch, data);
                // 文本格被纵向子格撑开时，值向下填充
                for (int k=1;k<span;k++) put(row+k, col, v, cell, data);
                expandNode(ch, row, col+colOffset(cell,ch), data, null);
            }
        }
    }

    /** 预判：单元格 cell 在数据集 data 上需要的行数（含其纵向子格的撑开） */
    private int measureRows(CellModel cell, List<Map<String,Object>> data) {
        String type = cell.getType()==null?"text":cell.getType();
        String expand = cell.getExpand()==null?"none":cell.getExpand().toLowerCase();
        List<CellModel> children = childrenMap.getOrDefault(cell.getRef().toUpperCase(), new ArrayList<>());
        if ("dataset".equals(type) && "v".equals(expand)) {
            List<Object> values = distinct(data, cell.getField());
            if (values.isEmpty()) values = Collections.singletonList("");
            int total = 0;
            for (Object v : values) {
                List<Map<String,Object>> subset = filterByField(data, cell.getField(), v);
                int need = 1;
                for (CellModel ch : children) need = Math.max(need, measureRows(ch, subset));
                total += need;
            }
            return Math.max(1,total);
        }
        int need = 1;
        for (CellModel ch : children) need = Math.max(need, measureRows(ch, data));
        return need;
    }

    /** 子格相对父格的设计列偏移 */
    private int colOffset(CellModel parent, CellModel child) {
        int[] p = CellModel.parse(parent.getRef()), c = CellModel.parse(child.getRef());
        return c[0] - p[0];
    }
    private int rowOffset(CellModel parent, CellModel child) {
        int[] p = CellModel.parse(parent.getRef()), c = CellModel.parse(child.getRef());
        return c[1] - p[1];
    }

    /** 落格：写入渲染结果（含样式与数值格式） */
    private void put(int row, int col, Object value, CellModel cell, List<Map<String,Object>> rowData) {
        if (row<0 || col<0 || row>=300 || col>=80) return;
        RenderCell rc = new RenderCell(row, col, value, cell);
        rc.style = new LinkedHashMap<>(cell.getStyle());
        rc.colspan = cell.getColspan(); rc.rowspan = cell.getRowspan();
        Object fmt = cell.getStyle().get("format");
        if (fmt!=null && value instanceof Number) rc.value = FormulaEngine.format(value, String.valueOf(fmt));
        if (rowData!=null && !rowData.isEmpty()) rc.rowData = rowData.get(0);
        result.cells.put(row+"_"+col, rc);
    }

    private List<Object> distinct(List<Map<String,Object>> rows, String field) {
        LinkedHashSet<Object> set = new LinkedHashSet<>();
        for (Map<String,Object> r : rows) { Object v=r.get(field); set.add(v==null?"":v); }
        return new ArrayList<>(set);
    }

    private List<Map<String,Object>> filterByField(List<Map<String,Object>> rows, String field, Object value) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            Object v = r.get(field);
            if (Objects.equals(String.valueOf(v), String.valueOf(value))) out.add(r);
        }
        return out;
    }
}
