package com.rbi.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 扩展引擎单元测试 —— 验证 M03-02 核心机制
 */
public class ExpandEngineTest {

    private List<Map<String,Object>> salesData() {
        List<Map<String,Object>> rows = new ArrayList<>();
        rows.add(row("华北","北京","笔记本",1000.0));
        rows.add(row("华北","北京","显示器",500.0));
        rows.add(row("华北","天津","笔记本",800.0));
        rows.add(row("华东","上海","笔记本",1200.0));
        rows.add(row("华东","上海","显示器",600.0));
        rows.add(row("华东","江苏","笔记本",900.0));
        rows.add(row("华南","广东","笔记本",1500.0));
        return rows;
    }

    private Map<String,Object> row(String region,String province,String cat,Double amount) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("region",region); m.put("province",province);
        m.put("category",cat); m.put("amount",amount);
        return m;
    }

    private CellModel cell(String ref,String type,String field,String expand,String parent,String value) {
        CellModel c = new CellModel();
        c.setRef(ref); c.setType(type); c.setField(field);
        c.setExpand(expand); c.setParent(parent); c.setValue(value);
        return c;
    }

    /** 单层纵向扩展：大区去重向下展开 */
    @Test
    public void testVerticalExpand() {
        LayoutModel layout = new LayoutModel();
        layout.getCells().add(cell("A1","dataset","region","v","",null));
        ExpandEngine.Result r = new ExpandEngine().expand(layout, salesData());
        // 3 个大区：华北/华东/华南
        List<String> vals = new ArrayList<>();
        for (int i=0;i<r.rows;i++) {
            ExpandEngine.RenderCell c = r.at(i,0);
            if (c!=null) vals.add(String.valueOf(c.value));
        }
        assertEquals(Arrays.asList("华北","华东","华南"), vals);
    }

    /** 父子格联动纵向扩展：省份跟随大区分组 */
    @Test
    public void testParentChildExpand() {
        LayoutModel layout = new LayoutModel();
        layout.getCells().add(cell("A1","dataset","region","v","",null));
        layout.getCells().add(cell("B1","dataset","province","v","A1",null));
        ExpandEngine.Result r = new ExpandEngine().expand(layout, salesData());
        // 华北占行0-1(北京/天津? 实际北京、天津=>2行)；华东占行2-3；华南占行4
        // 验证：A列在行0=华北, 行2=华东, 行4=华南
        assertEquals("华北", String.valueOf(r.at(0,0).value));
        assertEquals("华东", String.valueOf(r.at(2,0).value));
        assertEquals("华南", String.valueOf(r.at(4,0).value));
        // B列省份在对应分组内
        assertEquals("北京", String.valueOf(r.at(0,1).value));
        assertEquals("天津", String.valueOf(r.at(1,1).value));
        assertEquals("上海", String.valueOf(r.at(2,1).value));
    }

    /** 横向扩展：类别向右展开 */
    @Test
    public void testHorizontalExpand() {
        LayoutModel layout = new LayoutModel();
        layout.getCells().add(cell("A1","dataset","category","h","",null));
        ExpandEngine.Result r = new ExpandEngine().expand(layout, salesData());
        List<String> vals = new ArrayList<>();
        for (int c=0;c<r.cols;c++) {
            ExpandEngine.RenderCell rc = r.at(0,c);
            if (rc!=null) vals.add(String.valueOf(rc.value));
        }
        assertEquals(Arrays.asList("笔记本","显示器"), vals);
    }

    /** 公式引擎：聚合、单元格引用、IF */
    @Test
    public void testFormulaEngine() {
        FormulaEngine.Context ctx = new FormulaEngine.Context();
        List<Map<String,Object>> rows = new ArrayList<>();
        rows.add(row("华北","北京","笔记本",1000.0));
        rows.add(row("华北","北京","显示器",500.0));
        rows.add(row("华北","天津","笔记本",800.0));
        ctx.rows(rows).cell("A1", 100).cell("B1", 200);

        assertEquals(2300.0, ((Number)FormulaEngine.eval("SUM(amount)", ctx)).doubleValue(), 0.01);
        assertEquals(3, ((Number)FormulaEngine.eval("COUNT(amount)", ctx)).intValue());
        assertEquals(766.67, ((Number)FormulaEngine.eval("AVG(amount)", ctx)).doubleValue(), 0.01);
        assertEquals(300.0, ((Number)FormulaEngine.eval("A1+B1", ctx)).doubleValue(), 0.01);
        assertEquals("高", FormulaEngine.eval("IF(SUM(amount)>2000,\"高\",\"低\")", ctx));
        assertEquals(1800.0, ((Number)FormulaEngine.eval("A1+B1+SUM(amount)/2.3*0+1500", ctx)).doubleValue()-0.0, 0.01);
    }

    /** 父格上下文中的公式聚合（SUM 在分组内） */
    @Test
    public void testFormulaInGroup() {
        LayoutModel layout = new LayoutModel();
        layout.getCells().add(cell("A1","dataset","region","v","",null));
        CellModel f = cell("B1","formula",null,"none","A1","SUM(amount)");
        f.getStyle().put("format", "#,##0.00");
        layout.getCells().add(f);
        ExpandEngine.Result r = new ExpandEngine().expand(layout, salesData());
        // 公式取第一个父格上下文（华北）=> 1000+500+800=2300
        ExpandEngine.RenderCell rc = r.at(0,1);
        assertNotNull(rc);
        assertEquals("2,300.00", String.valueOf(rc.value));
    }

    /** 单元格引用解析 */
    @Test
    public void testRefParse() {
        assertArrayEquals(new int[]{1,1}, CellModel.parse("A1"));
        assertArrayEquals(new int[]{3,2}, CellModel.parse("C2"));
        assertArrayEquals(new int[]{27,1}, CellModel.parse("AA1"));
        assertEquals("C2", CellModel.toRef(3,2));
        assertEquals("AA1", CellModel.toRef(27,1));
    }
}
