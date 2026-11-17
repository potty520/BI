package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;
import java.util.Date;;

@Data
@TableName("rbi_op_log")
public class OpLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String module;
    private String operation;
    private String ip;
    private Date createTime;
}
