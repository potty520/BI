package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 行级数据权限：某角色在某数据集上允许看到的列值集合。
 * 与 rbi_permission（资源可见性）互补：前者管"能看哪个报表"，后者管"能看哪些数据行"。
 */
@Data
@TableName("rbi_data_scope")
public class DataScope {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roleId;
    private Long datasetId;
    /** 过滤列名（标识符白名单校验） */
    private String columnName;
    /** 允许值；NULL 或空表示该列不限制 */
    private String scopeValue;
    /** values：列值白名单；expr：受限表达式 */
    private String scopeType;
    /** expr 模式的过滤条件，如 amount < 10000 AND region = '华北' */
    private String filterExpr;
    private Date createTime;
}
