package com.rbi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.CrossOrigin;

/**
 * 自研报表与BI平台 启动类
 */
@SpringBootApplication
@MapperScan("com.rbi.mapper")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RbiApplication {
    public static void main(String[] args) {
        SpringApplication.run(RbiApplication.class, args);
    }
}
