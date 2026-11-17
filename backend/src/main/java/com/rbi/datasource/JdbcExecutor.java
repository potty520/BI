package com.rbi.datasource;

import com.rbi.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * JDBC 数据源执行器 (M01-01 / M02-01)
 * 支持动态驱动类名（MySQL/Oracle/SQLServer/PostgreSQL/达梦/人大金仓/GaussDB），
 * 通过 DriverManager 直连，连接用后即关，避免连接池参数丢失。
 */
@Slf4j
@Component
public class JdbcExecutor {

    /** 驱动类名推荐映射 */
    public static final Map<String,String> DRIVERS = new LinkedHashMap<>();
    static {
        DRIVERS.put("mysql", "com.mysql.cj.jdbc.Driver");
        DRIVERS.put("oracle", "oracle.jdbc.OracleDriver");
        DRIVERS.put("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        DRIVERS.put("postgresql", "org.postgresql.Driver");
        DRIVERS.put("dm", "dm.jdbc.driver.DmDriver");          // 达梦
        DRIVERS.put("kingbase", "com.kingbase8.Driver");       // 人大金仓
        DRIVERS.put("gaussdb", "com.huawei.gauss200.jdbc.Driver"); // GaussDB
        DRIVERS.put("h2", "org.h2.Driver");
    }

    /** 测试连接 */
    public void testConnection(String driver, String url, String username, String password) {
        if (url == null || url.trim().isEmpty()) throw new BizException("JDBC URL 不能为空");
        try {
            if (driver != null && !driver.isEmpty()) Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new BizException("驱动类不可用: " + driver + "（请确认已将对应数据库驱动jar加入依赖）");
        }
        try (Connection conn = DriverManager.getConnection(url, username, password == null ? "" : password)) {
            if (conn.isClosed()) throw new BizException("连接失败");
        } catch (SQLException e) {
            throw new BizException("连接失败: " + e.getMessage());
        }
    }

    /** 执行查询，返回 List<Map> */
    public List<Map<String,Object>> query(String driver, String url, String username, String password, String sql) {
        if (sql == null || sql.trim().isEmpty()) throw new BizException("SQL 不能为空");
        if (driver != null && !driver.isEmpty()) {
            try { Class.forName(driver); }
            catch (ClassNotFoundException e) { throw new BizException("驱动类不可用: " + driver); }
        }
        List<Map<String,Object>> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, username, password == null ? "" : password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int count = md.getColumnCount();
            while (rs.next()) {
                Map<String,Object> row = new LinkedHashMap<>();
                for (int i=1;i<=count;i++) {
                    String label = md.getColumnLabel(i);
                    Object val = rs.getObject(i);
                    if (val instanceof java.sql.Date) val = val.toString();
                    else if (val instanceof Timestamp) val = val.toString().replaceFirst("\\.0+$","");
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
        if (driver != null && !driver.isEmpty()) {
            try { Class.forName(driver); }
            catch (ClassNotFoundException e) { throw new BizException("驱动类不可用: " + driver); }
        }
        try (Connection conn = DriverManager.getConnection(url, username, password == null ? "" : password);
             Statement st = conn.createStatement()) {
            return st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new BizException("SQL执行失败: " + e.getMessage());
        }
    }

    /** 批量执行（事务一致性 M06-03） */
    public int[] batch(String driver, String url, String username, String password, List<String> sqls) {
        if (driver != null && !driver.isEmpty()) {
            try { Class.forName(driver); }
            catch (ClassNotFoundException e) { throw new BizException("驱动类不可用: " + driver); }
        }
        try (Connection conn = DriverManager.getConnection(url, username, password == null ? "" : password)) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : sqls) st.addBatch(sql);
                int[] result = st.executeBatch();
                conn.commit();
                return result;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new BizException("批量执行失败(已回滚): " + e.getMessage());
        }
    }
}
