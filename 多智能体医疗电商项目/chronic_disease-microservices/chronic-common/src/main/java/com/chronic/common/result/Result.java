package com.chronic.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果封装
 * <p>
 * 所有 Controller 返回此对象，前端按 code 判断业务状态：
 * <ul>
 *   <li>200: 成功</li>
 *   <li>4xx: 客户端错误（参数校验、业务规则等）</li>
 *   <li>5xx: 服务端错误（系统异常、第三方服务不可用等）</li>
 * </ul>
 * </p>
 *
 * @param <T> 响应数据类型
 * @author chronic
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码，200 表示成功 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 响应数据 */
    private T data;

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success(String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}