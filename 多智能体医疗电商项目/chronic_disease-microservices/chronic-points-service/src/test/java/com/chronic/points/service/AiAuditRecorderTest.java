package com.chronic.points.service;

import com.chronic.common.result.Result;
import com.chronic.points.feign.AuditFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 代查留痕的契约：审计失败绝不能影响用户本次查询（审计是增强，不是业务前置条件），
 * 但请求本身必须发给 shop-service（否则"审计看起来在跑，其实一条没落"）。
 *
 * @author chronic
 */
@ExtendWith(MockitoExtension.class)
class AiAuditRecorderTest {

    @Mock
    private AuditFeignClient auditFeignClient;

    private AiAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new AiAuditRecorder(auditFeignClient);
    }

    @Test
    void recordAssetQuery_sendsAiReadAuditForTheQueriedUser() {
        when(auditFeignClient.record(anyLong(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Result.success(true));

        recorder.recordAssetQuery(7L, "points");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        // operator 记为用户本人（是他在问），action 标明这次读取是 AI 发起的
        verify(auditFeignClient).record(eq(7L), eq("AI_QUERY_ASSET"), eq("USER_ASSET"), eq(7L), detail.capture());
        assertTrue(detail.getValue().contains("operator=AI"), detail.getValue());
        assertTrue(detail.getValue().contains("field=points"), detail.getValue());
    }

    @Test
    void record_doesNotThrow_whenAuditServiceIsDown() {
        when(auditFeignClient.record(anyLong(), anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertDoesNotThrow(() -> recorder.recordAssetQuery(7L, "account"));
    }

    @Test
    void record_doesNotThrow_whenRemoteWriteFailed() {
        when(auditFeignClient.record(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(Result.success(false));

        assertDoesNotThrow(() -> recorder.recordAssetQuery(7L, "account"));
    }

    @Test
    void record_doesNotThrow_whenRemoteReturnsErrorOrNull() {
        when(auditFeignClient.record(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(Result.error("boom"))
                .thenReturn(null);

        assertDoesNotThrow(() -> recorder.recordAssetQuery(7L, "account"));
        assertDoesNotThrow(() -> recorder.recordAssetQuery(7L, "account"));
    }
}
