package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;
import java.util.Date;;

@Data
@TableName("rbi_schedule")
public class Schedule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long reportId;
    private String cron;
    private String type;
    private String receiver;
    private Integer enabled;
    private Date lastRunTime;
    private Date nextRunTime;
}
