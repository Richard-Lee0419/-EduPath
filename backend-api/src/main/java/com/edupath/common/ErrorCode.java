package com.edupath.common;

public enum ErrorCode {
    PARAM_ERROR(40001, "参数错误"),
    UNAUTHORIZED(40100, "未登录或登录态无效"),
    NOT_FOUND(40400, "资源不存在"),
    EXTERNAL_SERVICE_ERROR(50200, "外部服务调用失败"),
    INTERNAL_ERROR(50000, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
