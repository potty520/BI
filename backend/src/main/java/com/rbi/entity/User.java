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
    /** 1 = 首次登录或管理员重置后必须先修改密码 */
    private Integer mustChangePwd;
    /** 找回密码邮箱 */
    private String email;
    /** 令牌版本：改密/踢下线时 +1，旧令牌立即失效 */
    private Integer tokenVersion;
    /** TOTP 密钥（Base32） */
    private String mfaSecret;
    /** 1 = 已启用二次验证 */
    private Integer mfaEnabled;
    /** 最近一次改密时间，用于密码过期判定 */
    private Date pwdUpdatedAt;
    /** 该用户的密码有效期（天），为空则用全局配置 */
    private Integer pwdExpireDays;
    private Date createTime;
}
