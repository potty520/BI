package com.rbi.controller;

import com.rbi.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开健康检查（无需登录）。
 * 只暴露存活信息，不包含 JVM 内存、节点地址等运维细节——那些在 /api/admin/health。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public R<?> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        // 用 JVM 运行时长，而不是本类首次加载时间（静态初始化会在第一次请求时才执行，容易误报）
        m.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("jvm", ManagementFactory.getRuntimeMXBean().getVmName());
        return R.ok(m);
    }
}
