package com.chronic.common.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务异常类
 * <p>
 * 用于抛出可预期的业务规则错误（如库存不足、余额不够、重复签到等），
 * 由 {@link com.chronic.common.handler.GlobalExceptionHandler} 统一捕获并返回友好提示。
 * </p>
 *
 * @author chronic
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BusinessException extends RuntimeException {

    /** 业务状态码，默认 400（业务规则错误客户端可修复，500 留给未知系统异常） */
    private Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}