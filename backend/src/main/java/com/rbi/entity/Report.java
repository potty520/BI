package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("rbi_report")
public class Report {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long directoryId;
    private Long datasetId;
    private String layoutJson;
    private String paramsJson;
    private Integer status;
    private Integer currentVersion;
    private String createBy;
    private Date createTime;
    private Date updateTime;

    @TableField(exist = false)
    private String datasetName;
}
