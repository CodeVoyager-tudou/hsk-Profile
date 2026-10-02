package com.chronic.common.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void success_shouldReturnCode200() {
        Result<?> result = Result.success();
        assertEquals(200, result.getCode());
        assertEquals("success", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void success_withData_shouldReturnData() {
        Result<String> result = Result.success("hello");
        assertEquals(200, result.getCode());
        assertEquals("hello", result.getData());
    }

    @Test
    void success_withMessageAndData() {
        Result<Integer> result = Result.success("ok", 42);
        assertEquals(200, result.getCode());
        assertEquals("ok", result.getMessage());
        assertEquals(42, result.getData());
    }

    @Test
    void error_shouldReturnCode500() {
        Result<?> result = Result.error("something wrong");
        assertEquals(500, result.getCode());
        assertEquals("something wrong", result.getMessage());
    }

    @Test
    void error_withCustomCode() {
        Result<?> result = Result.error(404, "not found");
        assertEquals(404, result.getCode());
        assertEquals("not found", result.getMessage());
    }
}
