package com.rbi.config;

import com.rbi.entity.Datasource;
import com.rbi.mapper.DatasourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/**
 * 启动初始化器：
 * 1) 把 rbi.test-datasource 配置的密码同步到后台库 rbi_datasource 记录（避免在 SQL 脚本中写明文密码）；
 * 2) 在测试库建 demo_sales 表并灌入演示数据（仅当表不存在，保护填报数据）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbInitializer implements ApplicationRunner {
    private final DataSourceProvider test;
    private final DatasourceMapper datasourceMapper;

    @Override
    public void run(ApplicationArguments args) {
        syncDatasourcePassword();
        try {
            Class.forName(test.getDriver());
            try (Connection conn = DriverManager.getConnection(test.url(), test.username(), test.password())) {
                // 探测表是否存在
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
