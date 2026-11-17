package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 找回密码令牌（一次性、带过期） */
@Data
@TableName("rbi_reset_token")
public class ResetToken {
    @TableId(type = IdType.INPUT)
    private String token;
    private Long userId;
    private Date expireAt;
    private Integer used;
    private Date createTime;
}
