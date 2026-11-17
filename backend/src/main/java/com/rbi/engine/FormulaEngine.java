package com.rbi.engine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公式引擎 (M03-03)
 * 支持：
 *  - 聚合函数 SUM/AVG/COUNT/MAX/MIN（对单元格引用或数据集字段）
 *  - 逻辑 IF(cond, a, b)、比较运算
 *  - 字符串拼接 CONCAT / &
 *  - 单元格引用 A1、B2 与四则运算 + - * /
 *  - 数值格式
 * 采用递归下降解析，不依赖任何脚本引擎，安全可控。
 */
public class FormulaEngine {

    /** 求值上下文：单元格值映射 + 当前分组明细数据 */
    public static class Context {
        public Map<String, Object> cellValues = new HashMap<>();
        public List<Map<String, Object>> rows = new ArrayList<>();

        public Context cell(String ref, Object v) { cellValues.put(ref.toUpperCase(), v); return this; }
        public Context rows(List<Map<String, Object>> r) { rows = r == null ? new ArrayList<>() : r; return this; }
    }

    /** 对外入口：求值公式，返回 Object */
    public static Object eval(String expr, Context ctx) {
        if (expr == null || expr.trim().isEmpty()) return "";
        return new Parser(expr.trim(), ctx).parseExpression();
    }

    /** 按格式串格式化结果 */
    public static String format(Object v, String fmt) {
        if (v == null) return "";
        if (fmt == null || fmt.isEmpty() || "General".equalsIgnoreCase(fmt)) return String.valueOf(v);
        try {
            if (v instanceof Number) {
                java.text.DecimalFormat df = new java.text.DecimalFormat(fmt);
                return df.format(v);
            }
        } catch (Exception ignored) {}
        return String.valueOf(v);
    }

    // ---------------- 递归下降解析器 ----------------
    private static class Parser {
        private final String src;
        private final Context ctx;
        private int pos = 0;

        Parser(String src, Context ctx) { this.src = src; this.ctx = ctx; }

        Object parseExpression() {
            Object left = parseCompare();
            while (peek("+") || peek("-") || peek("&")) {
                String op = next();
                Object right = parseCompare();
                if ("&".equals(op)) {
                    left = str(left) + str(right);
                } else if ("+".equals(op)) {
                    // 字符串 + 也做拼接
                    if (left instanceof String || right instanceof String) left = str(left) + str(right);
                    else left = num(left) + num(right);
                } else if ("-".equals(op)) {
                    left = num(left) - num(right);
                }
            }
            return left;
        }

        Object parseCompare() {
            Object left = parseTerm();
            while (peek(">=") || peek("<=") || peek("!=") || peek("==") || peek(">") || peek("<") || peek("=")) {
                String op = peek(">=") || peek("<=") || peek("!=") || peek("==") ? src.substring(pos, pos+2) : src.substring(pos, pos+1);
                pos += op.length();
                Object right = parseTerm();
                int c = compare(left, right);
                switch (op) {
                    case ">": left = c > 0; break;
                    case "<": left = c < 0; break;
                    case ">=": left = c >= 0; break;
                    case "<=": left = c <= 0; break;
                    case "=": case "==": left = c == 0; break;
                    case "!=": left = c != 0; break;
                }
            }
            return left;
        }

        Object parseTerm() {
            Object left = parseFactor();
            while (peek("*") || peek("/") || peek("%")) {
                String op = next();
                double r = num(parseFactor());
                if ("*".equals(op)) left = num(left) * r;
                else if ("/".equals(op)) left = r == 0 ? 0 : num(left) / r;
                else left = num(left) % r;
            }
            return left;
        }

        Object parseFactor() {
            skipWs();
            if (peek("(")) {
                next();
                Object v = parseExpression();
                skipWs();
                if (peek(")")) next();
                return v;
            }
            if (peek("-")) { next(); return -num(parseFactor()); }
            if (peek("+")) { next(); return parseFactor(); }
            if (peek("\"")) {
                next();
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && !peek("\"")) sb.append(src.charAt(pos++));
                if (peek("\"")) next();
                return sb.toString();
            }
            if (peek("'")) {
                next();
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && !peek("'")) sb.append(src.charAt(pos++));
                if (peek("'")) next();
                return sb.toString();
            }
            // 数字 / 标识符（函数名或单元格引用或字段名）
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos)=='_' || src.charAt(pos)=='.')) pos++;
            if (start == pos) { pos++; return ""; }
            String token = src.substring(start, pos);
            skipWs();
            if (peek("(")) { // 函数调用
                next();
                List<Object> args = new ArrayList<>();
                skipWs();
                if (!peek(")")) {
                    do { args.add(parseExpression()); skipWs(); } while (peek(",") && (next()!=null));
                }
                if (peek(")")) next();
                return callFunction(token, args);
            }
            return resolveToken(token);
        }

        private Object callFunction(String name, List<Object> args) {
            String fn = name.toUpperCase();
            switch (fn) {
                case "SUM": return aggregate(args, Agg.SUM);
                case "AVG": case "AVERAGE": return aggregate(args, Agg.AVG);
                case "COUNT": return aggregate(args, Agg.COUNT);
                case "MAX": return aggregate(args, Agg.MAX);
                case "MIN": return aggregate(args, Agg.MIN);
                case "IF":
                    boolean cond = args.size()>0 && toBool(args.get(0));
                    return cond ? (args.size()>1?args.get(1):"") : (args.size()>2?args.get(2):"");
                case "CONCAT":
                case "CAT": {
                    StringBuilder sb = new StringBuilder();
                    for (Object a : args) sb.append(str(a));
                    return sb.toString();
                }
                case "ROUND":
                    if (args.size()>=2) { double f = Math.pow(10, (int)num(args.get(1))); return Math.round(num(args.get(0))*f)/f; }
                    return args.isEmpty()?0:(double)Math.round(num(args.get(0)));
                case "ABS": return args.isEmpty()?0:Math.abs(num(args.get(0)));
                case "LEN": return args.isEmpty()?0:str(args.get(0)).length();
                default:
                    // 未知函数：返回第一个参数
                    return args.isEmpty()? "":args.get(0);
            }
        }

        private enum Agg { SUM, AVG, COUNT, MAX, MIN }
        private Object aggregate(List<Object> args, Agg agg) {
            if (args.isEmpty()) return 0;
            Object first = args.get(0);
            List<Double> nums = new ArrayList<>();
            // 参数可能是：集合(List)、标量、字段名对应的列数据
            collectNumbers(first, nums);
            for (int i=1;i<args.size();i++) collectNumbers(args.get(i), nums);
            switch (agg) {
                case COUNT: return nums.size();
                case SUM: { double s=0; for(double n:nums) s+=n; return s; }
                case AVG: { if(nums.isEmpty()) return 0; double s=0; for(double n:nums) s+=n; return s/nums.size(); }
                case MAX: { if(nums.isEmpty()) return 0; double m=nums.get(0); for(double n:nums) m=Math.max(m,n); return m; }
                case MIN: { if(nums.isEmpty()) return 0; double m=nums.get(0); for(double n:nums) m=Math.min(m,n); return m; }
            }
            return 0;
        }

        private void collectNumbers(Object o, List<Double> out) {
            if (o == null) return;
            if (o instanceof Number) { out.add(((Number)o).doubleValue()); return; }
            if (o instanceof Collection) { for (Object x : (Collection<?>)o) collectNumbers(x, out); return; }
            String s = String.valueOf(o).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return;
            try { out.add(Double.parseDouble(s)); } catch (Exception ignored) {}
        }

        /** token 解析：单元格引用 / 数据集字段 / 常量 */
        private Object resolveToken(String token) {
            String t = token.toUpperCase();
            // 单元格引用 A1
            if (t.matches("[A-Z]+[0-9]+")) {
                Object v = ctx.cellValues.get(t);
                return v == null ? 0 : v;
            }
            if ("TRUE".equals(t)) return true;
            if ("FALSE".equals(t)) return false;
            // 数字
            try { return Double.parseDouble(token); } catch (Exception ignored) {}
            // 数据集字段：当前分组所有行的该字段值集合
            List<Object> col = new ArrayList<>();
            for (Map<String,Object> row : ctx.rows) {
                if (row.containsKey(token)) col.add(row.get(token));
            }
            if (!col.isEmpty()) return col;
            // 兜底：返回 0
            return 0;
        }

        private boolean peek(String s) { return src.regionMatches(pos, s, 0, s.length()); }
        private String next() { String s = src.substring(pos, pos+1); pos++; return s; }
        private void skipWs() { while (pos<src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }
    }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number)o).doubleValue();
        if (o instanceof Boolean) return ((Boolean)o)?1:0;
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static boolean toBool(Object o) {
        if (o instanceof Boolean) return (Boolean)o;
        if (o instanceof Number) return ((Number)o).doubleValue()!=0;
        return "true".equalsIgnoreCase(str(o)) || "1".equals(str(o)) || "yes".equalsIgnoreCase(str(o));
    }
    private static int compare(Object a, Object b) {
        if (a instanceof Number || b instanceof Number) return Double.compare(num(a), num(b));
        return str(a).compareTo(str(b));
    }
}
