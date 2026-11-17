package com.rbi.config;

import com.rbi.entity.Datasource;
import com.rbi.mapper.DatasourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 启动初始化器：
 * 1) 对存量库做轻量结构迁移（新增审计字段），幂等；
 * 2) 把 rbi.test-datasource 配置的密码同步到后台库 rbi_datasource 记录（避免在 SQL 脚本中写明文密码）；
 * 3) 在测试库建 demo_sales 表并灌入演示数据（仅当表不存在，保护填报数据）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbInitializer implements ApplicationRunner {

    private static final String[][] COLUMN_MIGRATIONS = {
            // 表名, 列名, 定义
            {"rbi_op_log", "status", "VARCHAR(16)"},
            {"rbi_op_log", "duration_ms", "BIGINT"},
            {"rbi_op_log", "detail", "VARCHAR(1024)"},
            {"rbi_user", "must_change_pwd", "INT DEFAULT 0"},
            {"rbi_user", "email", "VARCHAR(128)"},
            {"rbi_user", "token_version", "INT DEFAULT 0"},
            {"rbi_user", "mfa_secret", "VARCHAR(64)"},
            {"rbi_user", "mfa_enabled", "INT DEFAULT 0"},
            {"rbi_user", "pwd_updated_at", "TIMESTAMP NULL"},
            {"rbi_user", "pwd_expire_days", "INT NULL"},
            {"rbi_data_scope", "scope_type", "VARCHAR(16) DEFAULT 'values'"},
            {"rbi_data_scope", "filter_expr", "VARCHAR(512)"},
            {"rbi_schedule", "locked_until", "TIMESTAMP NULL"},
            {"rbi_schedule", "retry_count", "INT DEFAULT 0"},
            {"rbi_schedule", "max_retries", "INT DEFAULT 3"},
            {"rbi_schedule", "last_error", "VARCHAR(512)"}
    };

    private final DataSource dataSource;
    private final DataSourceProvider test;
    private final DatasourceMapper datasourceMapper;

    @Override
    public void run(ApplicationArguments args) {
        migrateMetaSchema();
        syncDatasourcePassword();
        initTestDatabase();
    }

    /** 元数据库结构迁移：为老库补齐审计与账号字段（新库由 schema.sql 直接建好） */
    private void migrateMetaSchema() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            for (String[] m : COLUMN_MIGRATIONS) {
                if (!columnExists(md, m[0], m[1])) {
                    try (Statement st = conn.createStatement()) {
                        st.execute("ALTER TABLE " + m[0] + " ADD COLUMN " + m[1] + " " + m[2]);
                        log.info("[迁移] {}.{} 新增字段", m[0], m[1]);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[迁移] 结构检查失败（如为新库可忽略）: {}", e.getMessage());
        }
    }

    private boolean columnExists(DatabaseMetaData md, String table, String column) throws SQLException {
        String[] patterns = {table, table.toUpperCase()};
        for (String t : patterns) {
            try (ResultSet rs = md.getColumns(null, null, t, null)) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
                }
            }
        }
        return false;
    }

    /** 把配置中的测试库密码同步到数据源记录（脚本中不存明文密码） */
    private void syncDatasourcePassword() {
        try {
            Datasource ds = datasourceMapper.selectById(1L);
            if (ds == null) return;
            ds.setPasswordEnc(test.password());
            ds.setJdbcUrl(test.url());
            ds.setUsername(test.username());
            ds.setDriverClass(test.driver());
            datasourceMapper.updateById(ds);
            log.info("[数据源] 已按配置同步演示测试库连接参数");
        } catch (Exception e) {
            log.warn("[数据源] 同步连接参数失败: {}", e.getMessage());
        }
    }

    /** 演示业务库初始化（表不存在时才执行，保护填报数据） */
    private void initTestDatabase() {
        try {
            Class.forName(test.getDriver());
            try (Connection conn = java.sql.DriverManager.getConnection(test.url(), test.username(), test.password())) {
                boolean exists = false;
                try (ResultSet rs = conn.getMetaData().getTables(null, null, "demo_sales", new String[]{"TABLE"})) {
                    exists = rs.next();
                }
                if (exists) {
                    log.info("[测试库] demo_sales 已存在，跳过初始化");
                    return;
                }
                log.info("[测试库] 初始化 demo_sales 表与演示数据 -> {}", test.url());
                execScript(conn, read("db/testdb_schema.sql"));
                execScript(conn, read("db/testdb_data.sql"));
                log.info("[测试库] 初始化完成");
            }
        } catch (Exception e) {
            log.error("[测试库] 初始化失败（不影响平台启动，但演示数据不可用）", e);
        }
    }

    private String read(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 按分号切分执行 SQL 脚本（跳过注释行） */
    private void execScript(Connection conn, String script) throws SQLException {
        String[] parts = script.split(";");
        try (Statement st = conn.createStatement()) {
            for (String raw : parts) {
                StringBuilder sb = new StringBuilder();
                for (String line : raw.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("--")) continue;
                    sb.append(line).append("\n");
                }
                String sql = sb.toString().trim();
                if (!sql.isEmpty()) st.execute(sql);
            }
        }
    }
}
