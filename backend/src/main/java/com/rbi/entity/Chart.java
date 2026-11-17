package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;
import java.util.Date;;

@Data
@TableName("rbi_chart")
public class Chart {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
    private Long datasetId;
    private String configJson;
    private Long dashboardId;
    private Date createTime;
}
