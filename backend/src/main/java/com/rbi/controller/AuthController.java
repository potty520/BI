package com.rbi.controller;

import com.rbi.common.R;
import com.rbi.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService auth;

    @PostMapping("/login")
    public R<?> login(@RequestBody Map<String,String> body) {
        return R.ok(auth.login(body.get("username"), body.get("password")));
    }

    @GetMapping("/verify")
    public R<?> verify(@RequestHeader(value="Authorization", required=false) String token) {
        if (token==null || token.isEmpty()) return R.fail(401,"未提供token");
        return R.ok(auth.usernameFromToken(token.replaceFirst("^Bearer ","")));
    }
}
