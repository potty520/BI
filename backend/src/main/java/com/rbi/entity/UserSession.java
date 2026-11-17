package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 登录会话：每次登录生成一条，支持按会话（单设备）注销 */
@Data
@TableName("rbi_user_session")
public class UserSession {
    @TableId(type = IdType.INPUT)
    private String jti;
    private Long userId;
    private String username;
    private String ip;
    private String userAgent;
    private Date createdAt;
    private Date lastSeen;
    private Date expireAt;
    private Integer revoked;
}
