package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;
import java.util.Date;;

@Data
@TableName("rbi_report_version")
public class ReportVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reportId;
    private Integer version;
    private String layoutJson;
    private String remark;
    private String createBy;
    private Date createTime;
}
