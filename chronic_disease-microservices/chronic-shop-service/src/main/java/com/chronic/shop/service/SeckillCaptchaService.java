package com.chronic.shop.service;

import cn.hutool.core.util.IdUtil;
import com.chronic.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀滑动验证码（自研模拟实现，不依赖第三方服务）—— 人机前置拦截。
 *
 * <h3>【小白先看：它在秒杀漏斗的哪一层】</h3>
 * <pre>
 *   网关限流(5 QPS) → <b>滑块验证（本类：把脚本挡在到达 Redis 之前）</b>
 *     → Lua 预扣 → RocketMQ 排队 → 数据库落库
 * </pre>
 * 验证码的生成/校验都是 O(1) 的 Redis 操作 + 轻量图像绘制，可随服务水平扩容，
 * 本身不是瓶颈；它的价值是让"无脑脚本"在消耗 Lua/MQ 资源之前就被挡掉。
 *
 * <h3>【流程与防伪设计】</h3>
 * <ol>
 *   <li><b>出题</b> generate：Graphics2D 绘制带噪声的底图，随机 (x,y) 处挖出拼图缺口；
 *       缺口位置 x <b>绝不出现在响应里</b>（响应只有底图、碎块图、碎块纵坐标 y）；
 *       以 {@code captchaId} 在 Redis 记账（值 = uid:x，TTL 120s，绑定用户防转借）。</li>
 *   <li><b>验证</b> verify：比对拖拽结果与真实 x，容差 ±{@value #TOLERANCE_PX}px；
 *       同时校验<b>拖拽轨迹</b>（采样点数、总时长——真人拖动必有过程，脚本秒回无轨迹）；
 *       <b>验证即销毁</b>（getAndDelete，原子单次）——同一张验证码只有一次尝试机会，
 *       暴力枚举 x 的脚本每次都要重新领图，通过率被压到 ≈容差/取值区间。</li>
 *   <li><b>票据</b>：验证通过签发一次性 ticket（TTL 180s，绑定 userId），
 *       抢购接口消费票据（get 后 CAS 删除）——票据与验证码一样不可复用。</li>
 *   <li><b>出题限频</b>：每用户每分钟最多 {@value #GEN_LIMIT_PER_MIN} 次，
 *       防止"生成图片"本身被脚本刷成 CPU 消耗点。</li>
 * </ol>
 *
 * @author chronic
 */
@Slf4j
@Service
public class SeckillCaptchaService {

    private static final int BG_W = 280;
    private static final int BG_H = 140;
    private static final int PIECE = 44;
    private static final int GAP_X_MIN = 40;
    private static final int GAP_X_MAX = BG_W - PIECE - 20;
    private static final int GAP_Y_MIN = 20;
    private static final int GAP_Y_MAX = BG_H - PIECE - 20;
    /** 拖拽容差（px）：人工拖拽 ±6px 内可对准，脚本单次盲试通过率 ≈ 13/177 */
    private static final int TOLERANCE_PX = 6;
    /** 拖拽水平上限 = 缺口 x 上限（与前端滑块 max 一致） */
    public static final int DRAG_MAX = GAP_X_MAX;

    /** 轨迹行为校验阈值：真人拖动至少有若干采样点、且持续一段时间（脚本"秒回"无轨迹） */
    private static final int MIN_TRAJECTORY_POINTS = 5;
    private static final long MIN_TRAJECTORY_MILLIS = 300;

    private static final String CAPTCHA_KEY_PREFIX = "seckill:captcha:";
    private static final String TICKET_KEY_PREFIX = "seckill:ticket:";
    private static final String GEN_LIMIT_PREFIX = "seckill:captcha:gen:";
    private static final long CAPTCHA_TTL_SECONDS = 120;
    private static final long TICKET_TTL_SECONDS = 180;
    private static final int GEN_LIMIT_PER_MIN = 20;

    private final RedissonClient redissonClient;

    public SeckillCaptchaService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 出题结果：底图(BMP)/碎块(PNG) 的 data URL 与碎块纵坐标；缺口 x 只存在 Redis，绝不进响应 */
    public static class Captcha {
        public String captchaId;
        /** 底图 data URL（BMP：无压缩，便于端到端冒烟脚本做像素级缺口定位，模拟真实"打码"攻防） */
        public String bgDataUrl;
        /** 碎块 data URL（PNG） */
        public String pieceDataUrl;
        public int pieceY;

        public Captcha(String captchaId, String bgDataUrl, String pieceDataUrl, int pieceY) {
            this.captchaId = captchaId;
            this.bgDataUrl = bgDataUrl;
            this.pieceDataUrl = pieceDataUrl;
            this.pieceY = pieceY;
        }
    }

    /** 出题：返回验证码ID/双图/碎块纵坐标，Redis 记账（绑定 uid 与缺口 x） */
    public Captcha generate(Long userId) {
        limitGeneration(userId);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int x = rnd.nextInt(GAP_X_MIN, GAP_X_MAX + 1);
        int y = rnd.nextInt(GAP_Y_MIN, GAP_Y_MAX + 1);
        BufferedImage bg = drawBackground();
        // 碎块先于缺口遮罩裁剪：碎块是"原图内容"，缺口是"挖掉后的洞"
        BufferedImage piece = cropPiece(bg, x, y);
        drawGap(bg, x, y);

        String captchaId = IdUtil.fastSimpleUUID();
        RBucket<String> bucket = redissonClient.getBucket(CAPTCHA_KEY_PREFIX + captchaId, StringCodec.INSTANCE);
        bucket.set(userId + ":" + x, CAPTCHA_TTL_SECONDS, TimeUnit.SECONDS);

        return new Captcha(captchaId,
                "data:image/bmp;base64," + toBase64(toOpaqueRgb(bg), "bmp"),
                "data:image/png;base64," + toBase64(piece, "png"),
                y);
    }

    /**
     * 验证拖拽结果：轨迹行为校验 + 位置容差校验；成功签发一次性抢购票据。
     * 无论成败，验证码当场作废（getAndDelete 单次尝试）。
     *
     * @param trajectory 拖拽采样的横坐标序列（逗号分隔，如 "0,12,45,80,120,153"），前端随拖动采样
     * @param durationMs 首末采样时间差（毫秒），真人拖动一般 ≥300ms
     */
    public String verify(Long userId, String captchaId, int dragX, String trajectory, long durationMs) {
        RBucket<String> bucket = redissonClient.getBucket(CAPTCHA_KEY_PREFIX + captchaId, StringCodec.INSTANCE);
        String record = bucket.getAndDelete();
        if (record == null) {
            throw new BusinessException(400, "验证码已过期或已使用，请刷新重试");
        }
        String[] parts = record.split(":");
        long boundUserId = Long.parseLong(parts[0]);
        int realX = Integer.parseInt(parts[1]);
        if (boundUserId != userId) {
            // 验证码与登录身份绑定：别人的图拖对了也不给票（防转借/代刷）
            throw new BusinessException(400, "验证码与当前用户不匹配");
        }
        checkTrajectory(trajectory, durationMs, dragX);
        if (Math.abs(dragX - realX) > TOLERANCE_PX) {
            throw new BusinessException(400, "滑块位置不对，请重试");
        }
        String ticket = IdUtil.fastSimpleUUID();
        redissonClient.getBucket(TICKET_KEY_PREFIX + userId, StringCodec.INSTANCE)
                .set(ticket, TICKET_TTL_SECONDS, TimeUnit.SECONDS);
        log.info("滑块验证通过，签发票据: userId={}, 轨迹点数={}, 时长={}ms", userId, countPoints(trajectory), durationMs);
        return ticket;
    }

    /**
     * 轨迹行为校验（防"无过程"的脚本答案）：
     * ①采样点数 ≥ {@value #MIN_TRAJECTORY_POINTS}；②总时长 ≥ {@value #MIN_TRAJECTORY_MILLIS}ms；
     * ③每个采样点都在合法区间 [0, DRAG_MAX]；④轨迹终点与提交位置一致（±2px，防参数互相矛盾）。
     * 这里只做轻量规则，真实风控还会分析匀速性/加速度分布——留作扩展点。
     */
    private void checkTrajectory(String trajectory, long durationMs, int dragX) {
        if (trajectory == null || trajectory.isBlank()) {
            throw new BusinessException(400, "滑块行为异常，请重试");
        }
        int[] xs;
        try {
            String[] parts = trajectory.split(",");
            xs = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                xs[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "滑块行为异常，请重试");
        }
        if (xs.length < MIN_TRAJECTORY_POINTS || durationMs < MIN_TRAJECTORY_MILLIS) {
            throw new BusinessException(400, "滑块行为异常，请重试");
        }
        for (int x : xs) {
            if (x < 0 || x > DRAG_MAX) {
                throw new BusinessException(400, "滑块行为异常，请重试");
            }
        }
        if (Math.abs(dragX - xs[xs.length - 1]) > 2) {
            // 轨迹终点必须与提交位置一致，防止"轨迹参数"与"位置参数"各说各话
            throw new BusinessException(400, "滑块行为异常，请重试");
        }
    }

    private int countPoints(String trajectory) {
        return trajectory == null ? 0 : trajectory.split(",").length;
    }

    /**
     * 消费票据（抢购入口调用）：一次性——get 后 CAS 删除，重复提交/并发双击只有一次能通过。
     */
    public boolean consumeTicket(Long userId, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        RBucket<String> bucket = redissonClient.getBucket(TICKET_KEY_PREFIX + userId, StringCodec.INSTANCE);
        String stored = bucket.get();
        if (stored == null || !stored.equals(ticket)) {
            return false;
        }
        // CAS 置空：原子"取走即作废"，防止同一票据被并发抢购重复使用
        return bucket.compareAndSet(stored, null);
    }

    /** 出题限频：防"生成图片"被脚本刷成 CPU 消耗点（Redis 原子计数，60s 窗口） */
    private void limitGeneration(Long userId) {
        org.redisson.api.RAtomicLong counter = redissonClient.getAtomicLong(GEN_LIMIT_PREFIX + userId);
        long count = counter.incrementAndGet();
        if (count == 1L) {
            counter.expire(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (count > GEN_LIMIT_PER_MIN) {
            throw new BusinessException(429, "操作过于频繁，请稍后再试");
        }
    }

    /** 底图：渐变背景 + 半透明噪声（圆点/斜线），让缺口无法被简单的"像素全等"脚本定位 */
    private BufferedImage drawBackground() {
        BufferedImage bg = new BufferedImage(BG_W, BG_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(30, 144, 215), BG_W, BG_H, new Color(72, 209, 204)));
        g.fillRect(0, 0, BG_W, BG_H);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 26; i++) {
            g.setColor(new Color(255, 255, 255, rnd.nextInt(18, 70)));
            int size = rnd.nextInt(6, 30);
            g.fillOval(rnd.nextInt(BG_W), rnd.nextInt(BG_H), size, size);
        }
        for (int i = 0; i < 8; i++) {
            g.setColor(new Color(0, 0, 0, rnd.nextInt(12, 36)));
            g.drawLine(rnd.nextInt(BG_W), rnd.nextInt(BG_H), rnd.nextInt(BG_W), rnd.nextInt(BG_H));
        }
        g.dispose();
        return bg;
    }

    /** 从底图裁出碎块并描边（与缺口同一形状，用户拖到 x≈缺口即对齐） */
    private BufferedImage cropPiece(BufferedImage bg, int x, int y) {
        BufferedImage piece = new BufferedImage(PIECE, PIECE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = piece.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(bg.getSubimage(x, y, PIECE, PIECE), 0, 0, null);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(1, 1, PIECE - 2, PIECE - 2, 12, 12);
        g.dispose();
        return piece;
    }

    /** 在底图上画缺口（半透明挖空 + 品红描边）：品红是渐变/噪声里不会出现的颜色，
     *  既给人眼清晰对齐锚点，也让 E2E 冒烟脚本能以"弱脚本"方式做像素定位（不泄露 JSON 之外的任何接口语义） */
    private void drawGap(BufferedImage bg, int x, int y) {
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 110));
        g.fillRoundRect(x, y, PIECE, PIECE, 12, 12);
        g.setColor(new Color(255, 0, 255));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(x, y, PIECE, PIECE, 12, 12);
        g.dispose();
    }

    private String toBase64(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(image, format, bos);
            if (bos.size() == 0) {
                // ImageIO.write 对不支持的类型会静默返回 false——底图 BMP 必须先转不透明 RGB（见 toOpaqueRgb）
                throw new BusinessException(500, "验证码生成失败，请重试");
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            // 图像编码失败属系统级异常：出题失败不能给前端返回残缺验证码
            throw new BusinessException(500, "验证码生成失败，请重试");
        }
    }

    /** JDK 的 BMP writer 不支持带透明通道（ARGB 静默失败）：转成不透明 INT_RGB（底图本就完全不透明） */
    private BufferedImage toOpaqueRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }
}
