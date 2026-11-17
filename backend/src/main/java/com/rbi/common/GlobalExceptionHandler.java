package com.rbi.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * 设计要点：
 *  1. HTTP 状态码与业务码保持一致（401/403/400/404/429/500），便于网关与前端统一处理；
 *  2. 系统异常只回 "服务器内部错误 + traceId"，不把 SQL / 堆栈信息泄露给调用方，
 *     完整堆栈按 traceId 打到服务端日志，便于定位。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<?>> handleBiz(BizException e) {
        HttpStatus status = resolveStatus(e.getCode());
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("业务异常(500): {}", e.getMessage(), e);
        } else {
            log.warn("业务异常({}): {}", e.getCode(), e.getMessage());
        }
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<R<?>> handleBind(Exception e) {
        String msg;
        if (e instanceof MethodArgumentNotValidException) {
            msg = ((MethodArgumentNotValidException) e).getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        } else {
            msg = ((BindException) e).getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        }
        return ResponseEntity.badRequest().body(R.fail(400, msg.isEmpty() ? "参数校验失败" : msg));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<R<?>> handleBadRequest(Exception e) {
        log.warn("请求参数错误: {}", e.getMessage());
        return ResponseEntity.badRequest().body(R.fail(400, "请求参数格式不正确"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<?>> handleException(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.error("系统异常 traceId={}", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "服务器内部错误，请联系管理员（traceId: " + traceId + "）"));
    }

    private HttpStatus resolveStatus(int code) {
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null || status.is2xxSuccessful() || status.is3xxRedirection()) {
            return HttpStatus.BAD_REQUEST;
        }
        return status;
    }
}
