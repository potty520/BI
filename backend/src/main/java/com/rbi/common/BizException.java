package com.rbi.common;

/**
 * 业务异常
 */
public class BizException extends RuntimeException {
    private int code = 500;

    public BizException(String message) {
        super(message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
