package com.chronic.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.common.oss.OssStorageService;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.mapper.MedicineMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

/**
 * 药品管理 Controller，提供药品分页查询和详情查询
 *
 * @author chronic
 */
@Tag(name = "药品管理")
@RestController
@RequestMapping("/shop")
@RequiredArgsConstructor
public class MedicineController {

    private final MedicineMapper medicineMapper;

    /**
     * OSS 图片存储。Optional 注入：aliyun.oss 四要素没配齐时 Bean 不存在，
     * 服务照常启动，上传接口会明确报"对象存储未启用"（与 RocketMQTemplate 可选依赖同一套路）。
     */
    private final Optional<OssStorageService> ossStorageService;

    @Operation(summary = "分页查询药品（支持关键词与分类筛选）")
    @GetMapping("/medicine/page")
    public Result<Page<Medicine>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "10") Integer pageSize,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String category) {
        Page<Medicine> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Medicine> wrapper = new LambdaQueryWrapper<Medicine>()
                .eq(Medicine::getStatus, 1);
        if (category != null && !category.isEmpty()) {
            wrapper.eq(Medicine::getCategory, category);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Medicine::getName, keyword)
                    .or().like(Medicine::getGenericName, keyword)
                    .or().like(Medicine::getIndication, keyword));
        }
        wrapper.orderByDesc(Medicine::getId);
        return Result.success(medicineMapper.selectPage(page, wrapper));
    }

    @Operation(summary = "药品详情")
    @GetMapping("/medicine/{id}")
    public Result<Medicine> getById(@PathVariable Long id) {
        return Result.success(medicineMapper.selectById(id));
    }

    @Operation(summary = "上传药品图片（multipart，存阿里云 OSS 并回写 image_url）")
    @PostMapping("/medicine/{id}/image")
    public Result<String> uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        OssStorageService oss = ossStorageService
                .orElseThrow(() -> new BusinessException(503, "对象存储未启用，请配置 aliyun.oss 后重启"));
        // 先查归属：不存在/已下架的药品直接 404，避免先传 OSS 再发现药品不存在留下孤儿图
        if (medicineMapper.selectById(id) == null) {
            throw new BusinessException(404, "药品不存在");
        }
        String url = oss.upload("medicine", file);
        if (medicineMapper.updateImageUrl(id, url) == 0) {
            throw new BusinessException(404, "药品不存在");
        }
        return Result.success(url);
    }
}