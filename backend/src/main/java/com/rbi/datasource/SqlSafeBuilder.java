package com.rbi.datasource;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全 SQL 参数替换器 (M02-01 / M04)
 * 数据集 SQL 支持 ${param} 占位符，替换时对参数值做类型识别与转义，
 * 严禁将用户输入直接拼接为可执行 SQL 片段。
 *  - 数值型参数：仅允许数字，否则置空
 *  - 字符串：单引号转义，并包裹为字面量
 *  - IN 多选：拆分为多个字面量
 *  - 特殊：${date_filter} 等"片段型"占位符由白名单模板生成，不接受任意输入
 */
public class SqlSafeBuilder {

    private static final Pattern PH = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    /** 参数定义：值 + 类型（value / number / in / like） */
    public static class Param {
        String value;
        String type;
        public Param(String value, String type) { this.value = value; this.type = type; }
        public static Param val(String v) { return new Param(v, "value"); }
        public static Param num(String v) { return new Param(v, "number"); }
        public static Param in(String v) { return new Param(v, "in"); }
        public static Param like(String v) { return new Param(v, "like"); }
    }

    /**
     * 替换 SQL 中的占位符
     * @param sql 原始 SQL，含 ${xxx}
     * @param params 参数映射
     * @param fragmentWhitelist 片段型占位符白名单（如 region_filter -> "AND region='华东'"），null 表示不允许
     */
    public static String build(String sql, Map<String,Param> params, Map<String,String> fragmentWhitelist) {
        if (sql == null) return "";
        Matcher m = PH.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String replacement;
            if (fragmentWhitelist != null && fragmentWhitelist.containsKey(key)) {
                replacement = fragmentWhitelist.get(key);
            } else {
                Param p = params == null ? null : params.get(key);
                replacement = escape(p);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String escape(Param p) {
        if (p == null || p.value == null || p.value.trim().isEmpty()) return "''";
        String v = p.value.trim();
        switch (p.type == null ? "value" : p.type) {
            case "number":
                return v.matches("-?\\d+(\\.\\d+)?") ? v : "0";
            case "in":
                String[] arr = v.split(",");
                List<String> lit = new ArrayList<>();
                for (String a : arr) {
                    String s = a.trim().replace("'", "''");
                    if (!s.isEmpty()) lit.add("'" + s + "'");
                }
                return lit.isEmpty() ? "''" : String.join(",", lit);
            case "like":
                v = v.replace("'", "''").replace("%", "\\%").replace("_", "\\_");
                return "'%" + v + "%' ESCAPE '\\\\'";
            case "fragment":
                // 片段型只能来自白名单，直接给空
                return " 1=1 ";
            case "value":
            default:
                return "'" + v.replace("'", "''") + "'";
        }
    }
}
