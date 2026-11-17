package com.rbi.security;

import com.rbi.common.BizException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败次数限制（内存实现，单节点有效）。
 * 同一账号在窗口期内连续失败达到阈值后临时锁定，避免在线暴力破解。
 */
@Component
public class LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 5 * 60 * 1000L;

    private static class Attempt {
        int failures;
        long lockedUntil;
        long windowStart;
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 锁定中则直接拒绝 */
    public void checkNotLocked(String username) {
        Attempt a = attempts.get(key(username));
        if (a == null) return;
        long now = System.currentTimeMillis();
        if (a.lockedUntil > now) {
            long seconds = (a.lockedUntil - now) / 1000 + 1;
            throw new BizException(429, "登录失败次数过多，请 " + seconds + " 秒后重试");
        }
    }

    public void recordFailure(String username) {
        long now = System.currentTimeMillis();
        Attempt a = attempts.computeIfAbsent(key(username), k -> {
            Attempt n = new Attempt();
            n.windowStart = now;
            return n;
        });
        synchronized (a) {
            if (now - a.windowStart > WINDOW_MS) {
                a.failures = 0;
                a.windowStart = now;
            }
            a.failures++;
            if (a.failures >= MAX_FAILURES) {
                a.lockedUntil = now + WINDOW_MS;
                a.failures = 0;
                a.windowStart = now;
            }
        }
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
