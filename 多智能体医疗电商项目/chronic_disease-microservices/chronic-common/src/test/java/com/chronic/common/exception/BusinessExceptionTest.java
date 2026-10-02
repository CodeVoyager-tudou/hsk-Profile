package com.chronic.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BusinessExceptionTest {

    @Test
    void constructor_shouldSetMessageAndDefaultCode400() {
        BusinessException ex = new BusinessException("库存不足");
        assertEquals("库存不足", ex.getMessage());
        // 业务规则错误默认 400（客户端可修复），500 留给未知系统异常
        assertEquals(400, ex.getCode());
    }

    @Test
    void constructor_withCustomCode() {
        BusinessException ex = new BusinessException(400, "参数错误");
        assertEquals(400, ex.getCode());
        assertEquals("参数错误", ex.getMessage());
    }

    @Test
    void shouldExtendRuntimeException() {
        BusinessException ex = new BusinessException("test");
        assertInstanceOf(RuntimeException.class, ex);
    }
}
