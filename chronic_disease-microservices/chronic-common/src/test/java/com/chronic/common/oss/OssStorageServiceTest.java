package com.chronic.common.oss;

import com.aliyun.oss.OSS;
import com.chronic.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OssStorageService 单元测试：不连真实 OSS，mock 客户端，
 * 重点验证「校验规则」与「key/URL 的生成规则」（key 必须服务端生成、原始文件名不进 key）。
 *
 * @author chronic
 */
@ExtendWith(MockitoExtension.class)
class OssStorageServiceTest {

    private static final String ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com";
    private static final String BUCKET = "qk-parent-xcu";
    private static final String HOST = "oss-cn-hangzhou.aliyuncs.com";

    @Mock
    private OSS ossClient;

    private OssStorageService service;

    @BeforeEach
    void setUp() {
        OssProperties props = new OssProperties();
        props.setEndpoint(ENDPOINT);
        props.setBucketName(BUCKET);
        props.setAccessKeyId("test-id");
        props.setAccessKeySecret("test-secret");
        service = new OssStorageService(ossClient, props);
    }

    private MockMultipartFile image(String name, byte[] content) {
        return new MockMultipartFile("file", name, "image/png", content);
    }

    @Test
    @DisplayName("上传成功：返回公开 URL，key 服务端生成且原始文件名不进 key")
    void uploadReturnsPublicUrlAndServerGeneratedKey() {
        MockMultipartFile file = image("/etc/passwd.png", new byte[]{1, 2, 3});

        String url = service.upload("avatar/3", file);

        assertThat(url).startsWith("https://" + BUCKET + "." + HOST + "/avatar/3/");
        assertThat(url).endsWith(".png");

        ArgumentCaptor<String> bucket = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(ossClient).putObject(bucket.capture(), key.capture(), any(InputStream.class), any());
        assertThat(bucket.getValue()).isEqualTo(BUCKET);
        assertThat(key.getValue()).startsWith("avatar/3/");
        // 关键安全断言：原始文件名（可被伪造、可含路径）绝不进入对象 key
        assertThat(key.getValue()).doesNotContain("passwd");
    }

    @Test
    @DisplayName("空文件 / 超限 / 非白名单扩展名 / 非 image Content-Type 一律拒绝且不触达 OSS")
    void uploadRejectsInvalidInput() {
        assertThatThrownBy(() -> service.upload("avatar", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upload("avatar", image("a.png", new byte[0])))
                .isInstanceOf(BusinessException.class).hasMessageContaining("请选择");
        assertThatThrownBy(() -> service.upload("avatar", image("big.png", new byte[6 * 1024 * 1024])))
                .isInstanceOf(BusinessException.class).hasMessageContaining("5MB");
        assertThatThrownBy(() -> service.upload("avatar", image("evil.html", new byte[]{1})))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅支持图片格式");
        assertThatThrownBy(() -> service.upload("avatar",
                new MockMultipartFile("file", "a.png", "text/html", new byte[]{1})))
                .isInstanceOf(BusinessException.class).hasMessageContaining("图片文件");

        verify(ossClient, never()).putObject(anyString(), anyString(), any(InputStream.class), any());
    }

    @Test
    @DisplayName("deleteByUrl：本桶 URL 才删除，跨桶/畸形 URL 忽略")
    void deleteByUrlOnlyDeletesOwnBucketObjects() {
        service.deleteByUrl("https://" + BUCKET + "." + HOST + "/avatar/3/abc.png");
        verify(ossClient).deleteObject(eq(BUCKET), eq("avatar/3/abc.png"));

        service.deleteByUrl("https://other-bucket." + HOST + "/x.png");
        service.deleteByUrl("not-a-url");
        service.deleteByUrl(null);
        // 上面三次都不应再触发删除：deleteObject 恰好被调用 1 次
        verify(ossClient).deleteObject(anyString(), anyString());
    }

    @Test
    @DisplayName("hostOf：去掉协议与路径，仅留域名")
    void hostOfParsesEndpoint() {
        assertThat(OssStorageService.hostOf("https://" + HOST)).isEqualTo(HOST);
        assertThat(OssStorageService.hostOf(HOST + "/")).isEqualTo(HOST);
    }
}
