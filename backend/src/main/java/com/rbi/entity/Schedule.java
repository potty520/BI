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
    /** 多节点防重租约：该时间之前其它节点不领取本任务 */
    private Date lockedUntil;
    /** 当前连续失败次数 */
    private Integer retryCount;
    /** 最大重试次数（超过后停止并告警） */
    private Integer maxRetries;
    /** 最近一次错误摘要 */
    private String lastError;
}
