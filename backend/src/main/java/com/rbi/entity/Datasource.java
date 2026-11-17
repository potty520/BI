package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("rbi_datasource")
public class Datasource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
    private String driverClass;
    private String jdbcUrl;
    private String username;
    private String passwordEnc;
    private String filePath;
    private String apiConfig;
    private String remark;
    private Date createTime;
}
