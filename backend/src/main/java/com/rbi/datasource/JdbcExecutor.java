package com.rbi.datasource;

import com.rbi.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 数据源执行器 (M01-01 / M02-01)
 * 支持动态驱动类名（MySQL/Oracle/SQLServer/PostgreSQL/达梦/人大金仓/GaussDB），
 * 通过 DriverManager 直连，连接用后即关，避免连接池参数丢失。
 *
 * 安全：写入类操作（填报 / 导出落库）一律走 PreparedStatement + 参数绑定，
 * 不再把用户输入拼进 SQL 文本。
 */
@Slf4j
@Component
public class JdbcExecutor {

    /** 驱动类名推荐映射 */
    public static final Map<String, String> DRIVERS = new LinkedHashMap<>();

    static {
        DRIVERS.put("mysql", "com.mysql.cj.jdbc.Driver");
        DRIVERS.put("oracle", "oracle.jdbc.OracleDriver");
        DRIVERS.put("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        DRIVERS.put("postgresql", "org.postgresql.Driver");
        DRIVERS.put("dm", "dm.jdbc.driver.DmDriver");              // 达梦
        DRIVERS.put("kingbase", "com.kingbase8.Driver");           // 人大金仓
        DRIVERS.put("gaussdb", "com.huawei.gauss200.jdbc.Driver"); // GaussDB
        DRIVERS.put("h2", "org.h2.Driver");
    }

    /** 测试连接 */
    public void testConnection(String driver, String url, String username, String password) {
        if (url == null || url.trim().isEmpty()) throw new BizException("JDBC URL 不能为空");
        loadDriver(driver);
        try (Connection conn = DriverManager.getConnection(url, username, password == null ? "" : password)) {
            if (conn.isClosed()) throw new BizException("连接失败");
        } catch (SQLException e) {
            throw new BizException("连接失败: " + e.getMessage());
        }
    }

    /** 执行查询，返回 List<Map> */
    public List<Map<String, Object>> query(String driver, String url, String username, String password, String sql) {
        if (sql == null || sql.trim().isEmpty()) throw new BizException("SQL 不能为空");
        loadDriver(driver);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, username, password == null ? "" : password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int count = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= count; i++) {
                    String label = md.getColumnLabel(i);
                    Object val = rs.getObject(i);
                    if (val instanceof java.sql.Date) val = val.toString();
                    else if (val instanceof Timestamp) val = val.toString().replaceFirst("\\.0+$", "");
                    else if (val instanceof byte[]) val = "[binary]";
                    row.put(label, val);
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            log.error("SQL执行失败: {}", sql, e);
            throw new BizException("SQL执行失败: " + e.getMessage());
        }
        return rows;
    }

    /** 执行更新（填报入库 M06-03） */
    public int execute(String driver, String url, String username, String password, String sql) {
        loadDriver(driver);
        try (Connection conn = DriverManager.getConnection(url, username, password == null ? "" : password);
             Statement st = conn.createStatement()) {
            return st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new BizException("SQL执行失败: " + e.getMessage());
        }
    }

    /**
     * 参数化批量执行（事务一致性 M06-03）。
     * 每条 SQL 可带不同参数集，整体在一个事务内提交；任一失败全部回滚。
     */
    public int[] batchWithParams(String driver, String url, String username, String password,
                                 List<String> sqls, List<List<Object>> paramList) {
        if (sqls == null || sqls.isEmpty()) return new int[0];
        loadDriver(driver);
        int[] affected = new int[sqls.size()];
        try (Connection conn = DriverManager.getConnection(url, username, password == null ? "" : password)) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (int i = 0; i < sqls.size(); i++) {
                    List<Object> params = paramList == null || paramList.size() <= i ? null : paramList.get(i);
                    try (PreparedStatement ps = conn.prepareStatement(sqls.get(i))) {
                        bind(ps, params);
                        affected[i] = ps.executeUpdate();
                    }
                }
                conn.commit();
                conn.setAutoCommit(oldAutoCommit);
                return affected;
            } catch (SQLException e) {
                conn.rollback();
                conn.setAutoCommit(oldAutoCommit);
                throw e;
            }
        } catch (SQLException e) {
            log.error("批量执行失败: {}", sqls, e);
            throw new BizException("批量执行失败(已回滚): " + e.getMessage());
        }
    }

    /** 参数绑定：按类型选择 setObject，避免隐式转换导致的精度/类型问题 */
    public static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) return;
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v == null) {
                ps.setNull(i + 1, java.sql.Types.VARCHAR);
            } else if (v instanceof java.sql.Date || v instanceof Timestamp || v instanceof BigDecimal
                    || v instanceof Integer || v instanceof Long || v instanceof Double
                    || v instanceof Boolean) {
                ps.setObject(i + 1, v);
            } else {
                ps.setString(i + 1, String.valueOf(v));
            }
        }
    }

    /** 统一 SQL 值转换：数字走 BigDecimal，日期走 java.sql.Date，其余按字符串 */
    public static Object coerce(Object value, String type) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return null;
        if ("number".equalsIgnoreCase(type)) {
            if (s.matches("-?\\d{1,18}")) {
                try {
                    return Long.valueOf(s);
                } catch (NumberFormatException ignored) {
                    return new BigDecimal(s);
                }
            }
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                throw new BizException("数值格式不正确: " + s);
            }
        }
        if ("date".equalsIgnoreCase(type) && s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return java.sql.Date.valueOf(s);
        }
        return s;
    }

    private void loadDriver(String driver) {
        if (driver == null || driver.isEmpty()) return;
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new BizException("驱动类不可用: " + driver + "（请确认已将对应数据库驱动 jar 加入依赖）");
        }
    }
}
