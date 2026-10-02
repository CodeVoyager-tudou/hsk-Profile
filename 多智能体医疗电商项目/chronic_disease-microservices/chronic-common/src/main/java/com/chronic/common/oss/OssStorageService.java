package com.chronic.common.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.chronic.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 阿里云 OSS 存储服务 —— 图片上传 / 删除 / 拼公开访问 URL。
 *
 * <h3>【小白先看：一次上传发生了什么】</h3>
 * <pre>
 *   Controller 收到 multipart 文件
 *     → upload(keyPrefix, file)：
 *        ① 校验：非空 / 大小 ≤ maxFileSizeMb / 扩展名在白名单 / Content-Type 是 image/*
 *        ② 生成对象 key：{前缀}/{yyyyMM}/{32位UUID}.{扩展名}
 *           —— key 完全由服务端生成，**用户的原始文件名不进 key**（只取校验过的扩展名），
 *              从根上杜绝"文件名拼路径"类的目录穿越/覆盖攻击
 *        ③ putObject 上传
 *        ④ 返回公开访问 URL：https://{bucket}.{endpoint域名}/{key}
 * </pre>
 *
 * <p>前提：桶的读写权限是「公共读」（图片类桶的常规做法，&lt;img&gt; 标签可直接访问）。
 * 如果桶是私有的，把 {@link #publicUrl} 换成 generatePresignedUrl 签名 URL 即可，
 * 接口返回结构不变 —— 这也是接口返回"相对能力"的价值。</p>
 *
 * @author chronic
 */
@Slf4j
@RequiredArgsConstructor
public class OssStorageService {

    private final OSS ossClient;
    private final OssProperties properties;

    /**
     * 上传一张图片，返回可直接放进 &lt;img src&gt; 的公开 URL。
     *
     * @param keyPrefix 对象 key 前缀（服务端代码写死的常量，如 "avatar"、"medicine"），
     *                  不接受用户输入 —— 防止用户控制 key 结构
     * @param file      前端上传的 multipart 文件
     */
    public String upload(String keyPrefix, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }
        if (file.getSize() > properties.getMaxFileSizeMb() * 1024L * 1024L) {
            throw new BusinessException(400, "图片不能超过 " + properties.getMaxFileSizeMb() + "MB");
        }
        String ext = extractExtension(file);
        if (!allowedExtensions().contains(ext)) {
            throw new BusinessException(400, "仅支持图片格式：" + properties.getAllowedExtensions());
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            // 扩展名可伪造，Content-Type 再挡一道；双条件都过才放行
            throw new BusinessException(400, "仅支持上传图片文件");
        }

        String objectKey = buildObjectKey(keyPrefix, ext);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(contentType);
        try {
            ossClient.putObject(properties.getBucketName(), objectKey, file.getInputStream(), metadata);
        } catch (IOException e) {
            log.error("读取上传文件失败: key={}", objectKey, e);
            throw new BusinessException(500, "读取上传文件失败");
        } catch (Exception e) {
            // 网络/签名/权限等 OSS 侧异常：统一收口成业务异常，不让 SDK 异常直接穿透到前端
            log.error("OSS 上传失败: key={}", objectKey, e);
            throw new BusinessException(500, "图片上传失败，请稍后重试");
        }
        log.info("图片上传成功: key={}, size={}B", objectKey, file.getSize());
        return publicUrl(objectKey);
    }

    /**
     * 按公开 URL 删除对象（覆盖旧头像等场景可调用）。
     * 只删本桶内匹配的 key，跨桶/异常 URL 一律忽略 —— 删除是防御性操作，宁可不删不可误删。
     */
    public void deleteByUrl(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        String prefix = "https://" + properties.getBucketName() + "." + hostOf(properties.getEndpoint()) + "/";
        if (!url.startsWith(prefix)) {
            log.warn("URL 不属于本桶，跳过删除: {}", url);
            return;
        }
        String key = url.substring(prefix.length());
        try {
            ossClient.deleteObject(properties.getBucketName(), key);
        } catch (Exception e) {
            log.warn("OSS 删除失败（忽略，不阻断主流程）: key={}", key, e);
        }
    }

    /** 拼公开访问 URL：https://{bucket}.{endpoint域名}/{key} */
    public String publicUrl(String objectKey) {
        return "https://" + properties.getBucketName() + "." + hostOf(properties.getEndpoint()) + "/" + objectKey;
    }

    /**
     * 生成对象 key：{前缀}/{yyyyMM}/{UUID}.{ext}。
     * 按月分目录便于浏览和生命周期管理；UUID 保证唯一，同名重复上传互不覆盖。
     */
    private String buildObjectKey(String keyPrefix, String ext) {
        String prefix = keyPrefix == null ? "" : keyPrefix.replaceAll("^/+|/+$", "");
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        return prefix.isEmpty() ? month + "/" + name : prefix + "/" + month + "/" + name;
    }

    /** 取原始文件名的扩展名（小写）；只取扩展名，文件名本身绝不进 key */
    private String extractExtension(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Set<String> allowedExtensions() {
        return Arrays.stream(properties.getAllowedExtensions().split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** 从 endpoint 提取域名：https://oss-cn-hangzhou.aliyuncs.com → oss-cn-hangzhou.aliyuncs.com */
    static String hostOf(String endpoint) {
        String ep = endpoint == null ? "" : endpoint.trim();
        int scheme = ep.indexOf("://");
        String rest = scheme >= 0 ? ep.substring(scheme + 3) : ep;
        int slash = rest.indexOf('/');
        return (slash >= 0 ? rest.substring(0, slash) : rest).toLowerCase(Locale.ROOT);
    }
}
