package com.rbi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 测试业务库连接参数（M01 数据源之一）。
 * 后台元数据库走 Spring 主数据源；本配置指向独立的测试业务库（如 rbi_testdb）。
 * 应用内原本连接"内置演示库"的逻辑统一改为读取本配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rbi.test-datasource")
public class DataSourceProvider {
    /** MySQL 驱动 */
    private String driver = "com.mysql.cj.jdbc.Driver";
    private String url = "jdbc:mysql://127.0.0.1:3306/rbi_testdb?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    private String username = "root";
    private String password = "";

    public String driver() { return driver; }
    public String url() { return url; }
    public String username() { return username; }
    public String password() { return password == null ? "" : password; }
}
