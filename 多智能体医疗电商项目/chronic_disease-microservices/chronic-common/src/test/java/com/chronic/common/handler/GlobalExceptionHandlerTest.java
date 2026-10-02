package com.chronic.common.handler;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleBusinessException_shouldReturnErrorCodeAndMessage() {
        BusinessException ex = new BusinessException(400, "用户名已存在");
        Result<?> result = handler.handleBusinessException(ex);
        assertEquals(400, result.getCode());
        assertEquals("用户名已存在", result.getMessage());
    }

    @Test
    void handleBusinessException_shouldDefaultTo400() {
        BusinessException ex = new BusinessException("积分不足");
        Result<?> result = handler.handleBusinessException(ex);
        // 业务规则错误默认 400（客户端可修复），500 保留给未知系统异常
        assertEquals(400, result.getCode());
        assertEquals("积分不足", result.getMessage());
    }

    @Test
    void handleValidationException_shouldReturn400WithFieldMessage() {
        org.springframework.validation.BeanPropertyBindingResult bindingResult =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "loginDTO");
        bindingResult.addError(new org.springframework.validation.FieldError(
                "loginDTO", "username", "用户名不能为空"));
        org.springframework.web.bind.MethodArgumentNotValidException ex =
                new org.springframework.web.bind.MethodArgumentNotValidException(null, bindingResult);

        Result<?> result = handler.handleValidationException(ex);
        assertEquals(400, result.getCode());
        assertEquals("用户名不能为空", result.getMessage());
    }

    @Test
    void handleException_shouldReturn500WithGenericMessage() {
        Exception ex = new RuntimeException("unexpected error");
        Result<?> result = handler.handleException(ex);
        assertEquals(500, result.getCode());
        assertEquals("系统异常，请稍后重试", result.getMessage());
    }
}
