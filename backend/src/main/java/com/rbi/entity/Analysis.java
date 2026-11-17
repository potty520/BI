package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;
import java.util.Date;;

@Data
@TableName("rbi_analysis")
public class Analysis {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long datasetId;
    private String configJson;
    private Date createTime;
}
