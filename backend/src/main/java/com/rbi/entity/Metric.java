package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;
import java.util.Date;;

@Data
@TableName("rbi_metric")
public class Metric {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private String type;
    private String expression;
    private Long datasetId;
    private String unit;
    private String caliber;
    private String lineageJson;
    private Date createTime;
}
