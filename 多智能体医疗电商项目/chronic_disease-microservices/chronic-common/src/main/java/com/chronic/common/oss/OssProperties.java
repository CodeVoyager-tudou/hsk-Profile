package com.chronic.common.oss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OSS 配置（aliyun.oss.*）。
 *
 * <h3>【小白先看：密钥放哪】</h3>
 * AccessKey 一律走环境变量（OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET），绝不写进仓库 ——
 * 和 JWT_SECRET 同一条纪律：密钥进代码库等于把"往你桶里写文件的权限"公开了。
 * 四要素（endpoint / bucket / 两个 key）没配齐时整组 Bean 不创建，服务照常启动，
 * 只是图片上传接口会明确报"对象存储未启用"（见 ChronicCommonAutoConfiguration.OssEnabledCondition）。
 *
 * @author chronic
 */
@Data
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssProperties {

    /** Endpoint，如 https://oss-cn-hangzhou.aliyuncs.com */
    private String endpoint;

    /** 地域，如 cn-hangzhou（当前 SDK 用 endpoint 直连；region 备用于控制台/签名等场景） */
    private String region;

    /** 桶名称 */
    private String bucketName;

    /** AccessKey ID（环境变量 OSS_ACCESS_KEY_ID） */
    private String accessKeyId;

    /** AccessKey Secret（环境变量 OSS_ACCESS_KEY_SECRET） */
    private String accessKeySecret;

    /** 单文件大小上限（MB），默认 5MB */
    private long maxFileSizeMb = 5;

    /** 允许上传的扩展名白名单（逗号分隔），默认常见图片格式 */
    private String allowedExtensions = "jpg,jpeg,png,webp,gif";
}
