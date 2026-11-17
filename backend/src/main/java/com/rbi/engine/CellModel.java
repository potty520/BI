package com.rbi.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单元格模型 —— 报表设计器的核心元数据结构
 * 一个单元格可绑定数据集字段并设置扩展方向与父格，这是中国式复杂报表的核心机制(M03-02)
 */
public class CellModel {
    /** 单元格引用，如 A1、C3 */
    private String ref;
    /** 类型: text 文本 / dataset 数据集字段 / formula 公式 / image 图片 */
    private String type = "text";
    /** 文本内容 或 公式表达式 */
    private String value;
    /** 绑定的数据集字段名（type=dataset 时） */
    private String field;
    /** 扩展方向: v 纵向 / h 横向 / none 不扩展 */
    private String expand = "none";
    /** 父格引用，如 A3；为空表示不受父格约束 */
    private String parent = "";
    /** 聚合方式（当作为被汇总单元格）: SUM/AVG/COUNT/MAX/MIN/NONE */
    private String agg = "NONE";
    /** 样式 */
    private Map<String, Object> style = new LinkedHashMap<>();
    /** 是否左 Sonstige（横向扩展时子格跟随） */
    private Boolean leftParent = false;
    /** 单元格合并: 跨列数 / 跨行数 */
    private Integer colspan = 1;
    private Integer rowspan = 1;

    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getExpand() { return expand; }
    public void setExpand(String expand) { this.expand = expand; }
    public String getParent() { return parent == null ? "" : parent; }
    public void setParent(String parent) { this.parent = parent; }
    public String getAgg() { return agg == null ? "NONE" : agg; }
    public void setAgg(String agg) { this.agg = agg; }
    public Map<String, Object> getStyle() { return style == null ? (style = new LinkedHashMap<>()) : style; }
    public void setStyle(Map<String, Object> style) { this.style = style; }
    public Boolean getLeftParent() { return leftParent == null ? false : leftParent; }
    public void setLeftParent(Boolean leftParent) { this.leftParent = leftParent; }
    public Integer getColspan() { return colspan == null ? 1 : colspan; }
    public void setColspan(Integer colspan) { this.colspan = colspan; }
    public Integer getRowspan() { return rowspan == null ? 1 : rowspan; }
    public void setRowspan(Integer rowspan) { this.rowspan = rowspan; }

    /** 解析出列字母与行号（1-based） */
    public static int[] parse(String ref) {
        if (ref == null || ref.isEmpty()) return new int[]{0, 0};
        int i = 0;
        int col = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        int row = 0;
        while (i < ref.length() && Character.isDigit(ref.charAt(i))) {
            row = row * 10 + (ref.charAt(i) - '0');
            i++;
        }
        return new int[]{col, row};
    }

    public static String toRef(int col, int row) {
        StringBuilder sb = new StringBuilder();
        int c = col;
        while (c > 0) {
            int rem = (c - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            c = (c - 1) / 26;
        }
        return sb.append(row).toString();
    }
}
