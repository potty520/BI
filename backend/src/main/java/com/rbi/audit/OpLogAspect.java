package com.rbi.audit;

import com.rbi.entity.OpLog;
import com.rbi.mapper.OpLogMapper;
import com.rbi.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.Locale;

/**
 * 操作审计切面 (M09-04)
 *
 * 覆盖所有 controller 的写操作（GET 只读接口不记，避免日志爆炸），
 * 记录：操作人、模块、动作、来源 IP、结果、耗时、参数摘要。
 * 审计写入失败只打日志，绝不影响业务请求。
 *
 * 注意：不记录请求体内容，避免口令/敏感数据落库。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OpLogAspect {

    private final OpLogMapper opLogMapper;

    @Around("execution(* com.rbi.controller..*.*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        boolean success = true;
        String error = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            success = false;
            error = t.getMessage();
            throw t;
        } finally {
            try {
                write(pjp, start, success, error);
            } catch (Exception e) {
                log.warn("审计日志写入失败: {}", e.getMessage());
            }
        }
    }

    private void write(ProceedingJoinPoint pjp, long start, boolean success, String error) {
        HttpServletRequest req = currentRequest();
        if (req == null) return;
        String httpMethod = req.getMethod() == null ? "" : req.getMethod().toUpperCase(Locale.ROOT);
        if ("GET".equals(httpMethod) || "OPTIONS".equals(httpMethod) || "HEAD".equals(httpMethod)) return;

        String uri = req.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) return;

        OpLog entry = new OpLog();
        entry.setUsername(AuthContext.usernameOrSystem());
        entry.setModule(moduleOf(uri));
        entry.setOperation(truncate(httpMethod + " " + uri, 128));
        entry.setIp(clientIp(req));
        entry.setStatus(success ? "SUCCESS" : "FAIL");
        entry.setDurationMs(System.currentTimeMillis() - start);
        entry.setDetail(error == null ? methodName(pjp) : truncate("错误: " + error, 1024));
        entry.setCreateTime(new Date());
        opLogMapper.insert(entry);
    }

    private String moduleOf(String uri) {
        String[] parts = uri.split("/");
        // ["", "api", "reports", ...]
        if (parts.length > 2 && !parts[2].isEmpty()) {
            String m = parts[2];
            return truncate(m, 64);
        }
        return "api";
    }

    private String methodName(ProceedingJoinPoint pjp) {
        String cls = pjp.getSignature().getDeclaringType().getSimpleName();
        return truncate(cls + "#" + pjp.getSignature().getName(), 1024);
    }

    private HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) return null;
        return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            int comma = xff.indexOf(',');
            return truncate((comma > 0 ? xff.substring(0, comma) : xff).trim(), 64);
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.trim().isEmpty()) return truncate(real.trim(), 64);
        return truncate(req.getRemoteAddr(), 64);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
