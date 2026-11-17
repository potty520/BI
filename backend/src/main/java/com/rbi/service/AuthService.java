package com.rbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rbi.common.BizException;
import com.rbi.entity.Role;
import com.rbi.entity.User;
import com.rbi.entity.UserRole;
import com.rbi.mapper.RoleMapper;
import com.rbi.mapper.UserMapper;
import com.rbi.mapper.UserRoleMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 认证与RBAC服务 (M09-01)
 */
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    @Value("${jwt.secret}") private String secret;
    @Value("${jwt.expiration}") private long expiration;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public Map<String,Object> login(String username, String password) {
        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<>();
        w.eq(User::getUsername, username);
        User user = userMapper.selectOne(w);
        if (user == null || !encoder.matches(password, user.getPassword()))
            throw new BizException("用户名或密码错误");
        if (user.getStatus()!=null && user.getStatus()!=1) throw new BizException("账号已禁用");

        String token = Jwts.builder()
                .setSubject(username)
                .setExpiration(new Date(System.currentTimeMillis()+expiration))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();

        List<String> roles = new ArrayList<>();
        LambdaQueryWrapper<UserRole> ur = new LambdaQueryWrapper<>();
        ur.eq(UserRole::getUserId, user.getId());
        for (UserRole link : userRoleMapper.selectList(ur)) {
            Role role = roleMapper.selectById(link.getRoleId());
            if (role!=null) roles.add(role.getRoleCode());
        }

        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("username", user.getUsername());
        resp.put("realName", user.getRealName());
        resp.put("roles", roles);
        resp.put("deptId", user.getDeptId());
        return resp;
    }

    public String usernameFromToken(String token) {
        try {
            return Jwts.parserBuilder().setSigningKey(key()).build()
                    .parseClaimsJws(token).getBody().getSubject();
        } catch (Exception e) { throw new BizException(401, "登录已过期，请重新登录"); }
    }

    private SecretKey key() { return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); }

    /** 重置密码为 123456（演示用） */
    public void resetPassword(Long userId) {
        User u = new User();
        u.setId(userId);
        u.setPassword(encoder.encode("123456"));
        userMapper.updateById(u);
    }
}
