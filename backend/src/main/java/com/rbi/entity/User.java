package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;
import java.util.Date;;

@Data
@TableName("rbi_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String realName;
    private Long deptId;
    private Integer status;
    private Date createTime;
}
