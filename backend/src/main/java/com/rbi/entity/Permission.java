package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;

@Data
@TableName("rbi_permission")
public class Permission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roleId;
    private String resourceType;
    private Long resourceId;
    private String perm;
}
