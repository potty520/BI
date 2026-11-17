package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 列级权限与脱敏策略。
 * policy 取值：
 *  hidden        —— 不返回该列
 *  mask_all      —— 全掩码 ****
 *  mask_phone    —— 138****1234
 *  mask_idcard   —— 110***********1234
 *  mask_name     —— 张*
 *  mask_email    —— a***@example.com
 */
@Data
@TableName("rbi_column_policy")
public class ColumnPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roleId;
    private Long datasetId;
    private String columnName;
    private String policy;
    private Date createTime;
}
