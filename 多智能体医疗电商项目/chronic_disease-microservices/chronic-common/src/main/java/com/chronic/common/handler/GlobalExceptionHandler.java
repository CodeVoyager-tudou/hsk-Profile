package com.chronic.common.handler;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * <p>
 * 拦截所有 Controller 抛出的异常，统一包装为 Result 响应:
 * <ul>
 *   <li>{@link BusinessException} → 返回业务错误码和提示</li>
 *   <li>{@link MethodArgumentNotValidException} → 参数校验失败，返回 400 与字段提示</li>
 *   <li>{@link HttpMessageNotReadableException} → 请求体缺失/格式错，返回 400</li>
 *   <li>其他 {@link Exception} → 返回 500 通用错误</li>
 * </ul>
 * </p>
 *
 * @author chronic
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常，返回自定义错误码和提示信息
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.error("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理 @Valid 参数校验失败：取第一个字段错误作为提示，返回 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("请求参数不合法");
        log.warn("参数校验失败: {}", message);
        return Result.error(400, message);
    }

    /**
     * 处理请求体缺失/JSON 格式错误，返回 400（而非 500 系统异常）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体不可读: {}", e.getMessage());
        return Result.error(400, "请求体格式错误");
    }

    /**
     * 处理未知系统异常，返回 500 通用错误，不暴露内部异常细节
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统异常，请稍后重试");
    }
}
