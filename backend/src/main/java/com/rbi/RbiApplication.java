package com.rbi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 自研报表与BI平台 启动类
 *
 * 说明：CORS 统一由 WebConfig 按白名单配置，这里的 @CrossOrigin("*") 已移除。
 */
@SpringBootApplication
@MapperScan("com.rbi.mapper")
@EnableScheduling
public class RbiApplication {
    public static void main(String[] args) {
        SpringApplication.run(RbiApplication.class, args);
    }
}
