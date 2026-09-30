package com.chronic.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.oss.OssStorageService;
import com.chronic.common.result.Result;
import com.chronic.shop.dto.MedicineUpdateRequest;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.entity.SeckillActivity;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.mapper.SeckillActivityMapper;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.service.AdminAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 药品管理（管理端，/api/admin/** 由网关 ADMIN 角色闸门保护）。
 *
 * <p>双道角色防线：网关校验 JWT role 后注入 X-User-Role，本控制器再做一次
 * 显式校验（纵深防御——即使将来有人把路由配错、或服务被内网直连，仍挡得住）。
 * 所有写操作落 admin_audit 审计表。</p>
 *
 * @author chronic
 */
@Slf4j
@Tag(name = "管理-药品")
@RestController
@RequestMapping("/admin/medicine")
@RequiredArgsConstructor
public class AdminMedicineController {

    /** 各文本列的长度上限，与建表 DDL（及实体字段长度）保持一致，超长直接拒绝而不是靠数据库报错 */
    private static final int MAX_NAME_LEN = 100;
    private static final int MAX_CATEGORY_LEN = 50;
    private static final int MAX_MANUFACTURER_LEN = 200;
    /** 适应症/用法用量是 TEXT 列，上限按"够用且防滥用"取值（TEXT 实际能存 64KB） */
    private static final int MAX_TEXT_LEN = 2000;
    /** DECIMAL(10,2) 的上限（复核 P2-4）：整数位 8 位。超出时 MySQL 严格模式抛 Out of range，
     *  穿透到前端是一段数据库错误而不是业务提示，必须在入口拦住 */
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
    /** 库存/积分类 INT 字段的业务上限：远离 Integer.MAX_VALUE，够演示与真实业务用 */
    private static final int MAX_INT_FIELD = 999999;

    private final MedicineMapper medicineMapper;
    private final ShopOrderMapper shopOrderMapper;
    private final SeckillActivityMapper seckillActivityMapper;
    private final Optional<OssStorageService> ossStorageService;
    private final AdminAuditService adminAuditService;

    @Operation(summary = "药品分页（管理视角：含下架药品，可按状态过滤）")
    @GetMapping("/page")
    public Result<Page<Medicine>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "10") Integer pageSize,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        Page<Medicine> page = new Page<>(pageNum, Math.min(Math.max(pageSize, 1), 100));
        LambdaQueryWrapper<Medicine> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Medicine::getStatus, status);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Medicine::getName, keyword)
                    .or().like(Medicine::getGenericName, keyword));
        }
        wrapper.orderByDesc(Medicine::getId);
        return Result.success(medicineMapper.selectPage(page, wrapper));
    }

    @Operation(summary = "新增药品（名称与现金价必填；库存/积分缺省 0；新建即上架，可再下架）")
    @PostMapping
    public Result<Medicine> create(@RequestBody MedicineUpdateRequest request,
                                   @RequestHeader("X-User-Id") Long operatorId,
                                   @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        Medicine medicine = buildNewMedicine(request);
        rejectDuplicateName(medicine);
        medicineMapper.insert(medicine);
        adminAuditService.record(operatorId, "MEDICINE_CREATE", "MEDICINE", medicine.getId(),
                "name=" + medicine.getName() + ", price=" + medicine.getPrice()
                        + ", stock=" + medicine.getStock() + ", pointsPrice=" + medicine.getPointsPrice());
        return Result.success(medicine);
    }

    /**
     * 新增前的同名软防重（复核 P2-5）：medicine 表没有唯一键（业务上"同名不同厂家/规格"合法，
     * 硬加唯一键会挡住正常录入），但重复录入同名同厂家的药品后，AI 报价会把两个不同价格
     * 一起列出来 —— "报价必须确定"是数据型专家的前提，所以录入环节就挡掉。
     */
    private void rejectDuplicateName(Medicine medicine) {
        List<Medicine> sameName = medicineMapper.selectList(
                new LambdaQueryWrapper<Medicine>().eq(Medicine::getName, medicine.getName()));
        Optional<Medicine> dup = sameName.stream()
                .filter(m -> Objects.equals(m.getManufacturer(), medicine.getManufacturer()))
                .findFirst();
        if (dup.isPresent()) {
            throw new BusinessException("已存在同名药品（#" + dup.get().getId() + "），请改用编辑，"
                    + "或补充厂家/规格区分");
        }
    }

    @Operation(summary = "删除药品（被订单或秒杀活动引用时拒绝并提示改用下架；同时清理无人再用的图片）")
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> delete(@PathVariable Long id,
                                  @RequestHeader("X-User-Id") Long operatorId,
                                  @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        Medicine medicine = medicineMapper.selectById(id);
        if (medicine == null) {
            throw new BusinessException(404, "药品不存在");
        }

        // 为什么不是"想删就删"：订单与秒杀活动都用 medicine_id 指向药品，删掉会让历史账目、
        // 活动回填药名、销量统计一并断链。真正想"下架不卖"用 /status，这里只允许删"从没被用过"的。
        //
        // 先删、再验（复核 P1-6）：V1 建表无外键，"查引用 → 再删"两步之间有窗口——并发下单
        // 恰好落在 selectCount 与 deleteById 之间，就会留下指向已删行的订单。改成事务内先
        // deleteById（拿到该行排他锁，并发下单的 reduceStock 更新会阻塞到本事务提交），
        // 再数一次引用、>0 就抛异常回滚，窗口真正闭环。回滚后药品行恢复，不会误删。
        if (medicineMapper.deleteById(id) == 0) {
            throw new BusinessException(404, "药品不存在");
        }
        long orderRefs = shopOrderMapper.selectCount(
                new LambdaQueryWrapper<ShopOrder>().eq(ShopOrder::getMedicineId, id));
        if (orderRefs > 0) {
            throw new BusinessException("该药品有 " + orderRefs + " 笔历史订单引用，删除会让订单断链；请改用下架");
        }
        long activityRefs = seckillActivityMapper.selectCount(
                new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getMedicineId, id));
        if (activityRefs > 0) {
            throw new BusinessException("该药品有 " + activityRefs + " 个秒杀活动引用；请先处理活动，或改用下架");
        }

        deleteImageIfUnused(medicine);
        adminAuditService.record(operatorId, "MEDICINE_DELETE", "MEDICINE", id,
                "name=" + medicine.getName() + ", price=" + medicine.getPrice()
                        + ", stock=" + medicine.getStock() + ", status=" + medicine.getStatus());
        return Result.success(true);
    }

    /**
     * 删除药品时顺手清理它的图片对象：行都没了，图就成了孤儿
     * （`scripts/oss-orphan-scan.py` 也正是扫这类对象）。
     *
     * <p>两道护栏：只删本桶的 URL（{@code deleteByUrl} 内部校验），且**仅当没有别的药品
     * 用同一张图**时才删 —— 管理端完全可能把同一个 URL 填给两个药品，那时删一个会打坏另一个。
     * OSS 删除失败只告警不阻断：数据行已经删了，图片留着只是多占一点存储。</p>
     */
    private void deleteImageIfUnused(Medicine medicine) {
        String url = medicine.getImageUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        long otherRefs = medicineMapper.selectCount(new LambdaQueryWrapper<Medicine>()
                .eq(Medicine::getImageUrl, url)
                .ne(Medicine::getId, medicine.getId()));
        if (otherRefs > 0) {
            log.info("图片仍被其它药品引用，跳过删除: url={}, refs={}", url, otherRefs);
            return;
        }
        ossStorageService.ifPresent(oss -> oss.deleteByUrl(url));
    }

    @Operation(summary = "编辑药品（名称/通用名/分类/厂家/适应症/用法用量/现金价/积分价/返积分/库存）"
            + "；请求体里为 null 的字段不改；上下架与图片有各自接口")
    @PostMapping("/{id}/update")
    public Result<Boolean> update(@PathVariable Long id,
                                  @RequestBody MedicineUpdateRequest request,
                                  @RequestHeader("X-User-Id") Long operatorId,
                                  @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        Medicine before = medicineMapper.selectById(id);
        if (before == null) {
            throw new BusinessException(404, "药品不存在");
        }
        Medicine patch = buildPatch(request);
        String changes = describeChanges(before, patch);
        if (changes.isEmpty()) {
            // 提交值与库里一致（例如别人刚把它改成了同一个值）：不写库、不写审计，
            // 免得在审计表里留下一堆"什么都没改"的记录
            log.info("药品编辑未产生实际变更，跳过写库: id={}, operator={}", id, operatorId);
            return Result.success(true);
        }
        if (medicineMapper.updateEditable(id, patch) == 0) {
            throw new BusinessException(404, "药品不存在");
        }
        adminAuditService.record(operatorId, "MEDICINE_UPDATE", "MEDICINE", id, changes);
        return Result.success(true);
    }

    /**
     * 新增药品：与编辑共用同一套字段校验，但**名称与现金价必填**，
     * 库存/积分缺省 0（表默认值），新建即上架（想不卖就再点下架）。
     */
    private Medicine buildNewMedicine(MedicineUpdateRequest request) {
        Medicine medicine = new Medicine();
        medicine.setName(requiredText(request.getName(), "药品名称", MAX_NAME_LEN));
        if (medicine.getName() == null) {
            throw new BusinessException("药品名称不能为空");
        }
        medicine.setGenericName(optionalText(request.getGenericName(), "通用名", MAX_NAME_LEN));
        medicine.setCategory(optionalText(request.getCategory(), "分类", MAX_CATEGORY_LEN));
        medicine.setManufacturer(optionalText(request.getManufacturer(), "厂家", MAX_MANUFACTURER_LEN));
        medicine.setIndication(optionalText(request.getIndication(), "适应症", MAX_TEXT_LEN));
        medicine.setDosage(optionalText(request.getDosage(), "用法用量", MAX_TEXT_LEN));
        BigDecimal price = validatePrice(request.getPrice());
        if (price == null) {
            throw new BusinessException("现金价必填");
        }
        medicine.setPrice(price);
        medicine.setStock(defaultZero(validateNonNegative(request.getStock(), "库存")));
        medicine.setPointsPrice(defaultZero(validateNonNegative(request.getPointsPrice(), "积分兑换价")));
        medicine.setPointsReward(defaultZero(validateNonNegative(request.getPointsReward(), "返积分")));
        medicine.setStatus(1);
        return medicine;
    }

    private Integer defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 校验请求并转成"窄更新 patch"：只有非空字段进入 patch（null = 不改）。
     *
     * <p>校验放在这里而不是只靠前端：管理端接口同样可能被脚本直接调用，
     * 而价格/积分为负、名称为空这类脏值一旦写进商品表，商城页与 AI 报价都会跟着错。</p>
     */
    private Medicine buildPatch(MedicineUpdateRequest request) {
        Medicine patch = new Medicine();
        patch.setName(requiredText(request.getName(), "药品名称", MAX_NAME_LEN));
        patch.setGenericName(optionalText(request.getGenericName(), "通用名", MAX_NAME_LEN));
        patch.setCategory(optionalText(request.getCategory(), "分类", MAX_CATEGORY_LEN));
        patch.setManufacturer(optionalText(request.getManufacturer(), "厂家", MAX_MANUFACTURER_LEN));
        patch.setIndication(optionalText(request.getIndication(), "适应症", MAX_TEXT_LEN));
        patch.setDosage(optionalText(request.getDosage(), "用法用量", MAX_TEXT_LEN));
        patch.setPrice(validatePrice(request.getPrice()));
        patch.setStock(validateNonNegative(request.getStock(), "库存"));
        patch.setPointsPrice(validateNonNegative(request.getPointsPrice(), "积分兑换价"));
        patch.setPointsReward(validateNonNegative(request.getPointsReward(), "返积分"));
        if (isPatchEmpty(patch)) {
            throw new BusinessException("没有要修改的字段");
        }
        return patch;
    }

    /** 名称传了就不能是空白（防把商品改成无名）；null 仍表示不改 */
    private String requiredText(String value, String label, int maxLen) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            throw new BusinessException(label + "不能为空");
        }
        return checkLength(text, label, maxLen);
    }

    /** 可选文本：null=不改，空串=显式清空 */
    private String optionalText(String value, String label, int maxLen) {
        return value == null ? null : checkLength(value.trim(), label, maxLen);
    }

    private String checkLength(String text, String label, int maxLen) {
        if (text.length() > maxLen) {
            throw new BusinessException(label + "长度不能超过 " + maxLen + " 个字符");
        }
        return text;
    }

    private BigDecimal validatePrice(BigDecimal price) {
        if (price == null) {
            return null;
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("价格必须大于 0");
        }
        // 列是 DECIMAL(10,2)：多于两位小数会被 MySQL 静默四舍五入，这里直接拒绝，免得对账对不上
        if (price.stripTrailingZeros().scale() > 2) {
            throw new BusinessException("价格最多两位小数");
        }
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new BusinessException("价格超出上限（" + MAX_PRICE + "）");
        }
        return price;
    }

    private Integer validateNonNegative(Integer value, String label) {
        if (value != null && value < 0) {
            throw new BusinessException(label + "不能为负数");
        }
        if (value != null && value > MAX_INT_FIELD) {
            throw new BusinessException(label + "超出上限（" + MAX_INT_FIELD + "）");
        }
        return value;
    }

    private boolean isPatchEmpty(Medicine patch) {
        return patch.getName() == null && patch.getGenericName() == null && patch.getCategory() == null
                && patch.getManufacturer() == null && patch.getIndication() == null && patch.getDosage() == null
                && patch.getPrice() == null && patch.getStock() == null
                && patch.getPointsPrice() == null && patch.getPointsReward() == null;
    }

    /**
     * 生成审计明细：只记**真实发生变化的**列（值相同的提交不算变更），形如
     * {@code changed=[name,price]; name: 阿司匹林→阿司匹林肠溶片; price: 12.00→13.00}。
     *
     * <p>"改了什么"是审计最该回答的问题——只记"提交了 xx 字段"无法回答它。</p>
     */
    private String describeChanges(Medicine before, Medicine patch) {
        List<String> changed = new ArrayList<>();
        List<String> diff = new ArrayList<>();
        appendChange(changed, diff, "name", patch.getName(), before.getName());
        appendChange(changed, diff, "genericName", patch.getGenericName(), before.getGenericName());
        appendChange(changed, diff, "category", patch.getCategory(), before.getCategory());
        appendChange(changed, diff, "manufacturer", patch.getManufacturer(), before.getManufacturer());
        appendChange(changed, diff, "indication", patch.getIndication(), before.getIndication());
        appendChange(changed, diff, "dosage", patch.getDosage(), before.getDosage());
        appendChange(changed, diff, "price", patch.getPrice(), before.getPrice());
        appendChange(changed, diff, "stock", patch.getStock(), before.getStock());
        appendChange(changed, diff, "pointsPrice", patch.getPointsPrice(), before.getPointsPrice());
        appendChange(changed, diff, "pointsReward", patch.getPointsReward(), before.getPointsReward());
        if (changed.isEmpty()) {
            return "";
        }
        return "changed=" + changed + "; " + String.join("; ", diff);
    }

    private void appendChange(List<String> changed, List<String> diff, String field,
                              Object newValue, Object oldValue) {
        if (newValue == null || sameValue(newValue, oldValue)) {
            return;
        }
        changed.add(field);
        diff.add(field + ": " + oldValue + "→" + newValue);
    }

    /**
     * 值是否相同。<b>BigDecimal 必须按数值比</b>：{@code equals} 连 scale 一起比，
     * 于是提交 {@code 12.0} 与库里的 {@code 12.00} 会被判成"改价了"，
     * 写库不止、审计里还会留下 {@code price: 12.00→12} 这种根本没发生的变更（实测踩到）。
     */
    private boolean sameValue(Object newValue, Object oldValue) {
        if (newValue == null || oldValue == null) {
            return newValue == oldValue;
        }
        if (newValue instanceof BigDecimal && oldValue instanceof BigDecimal) {
            return ((BigDecimal) newValue).compareTo((BigDecimal) oldValue) == 0;
        }
        return Objects.equals(newValue, oldValue);
    }

    @Operation(summary = "上下架（1-上架 0-下架）")
    @PostMapping("/{id}/status")
    public Result<Boolean> updateStatus(@PathVariable Long id,
                                        @RequestParam Integer status,
                                        @RequestHeader("X-User-Id") Long operatorId,
                                        @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException("status 仅允许 0/1");
        }
        if (medicineMapper.updateStatus(id, status) == 0) {
            throw new BusinessException(404, "药品不存在");
        }
        adminAuditService.record(operatorId, "MEDICINE_STATUS", "MEDICINE", id, "status=" + status);
        return Result.success(true);
    }

    @Operation(summary = "上传药品图片（multipart，存 OSS 并回写 image_url）")
    @PostMapping("/{id}/image")
    public Result<String> uploadImage(@PathVariable Long id,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestHeader("X-User-Id") Long operatorId,
                                      @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        OssStorageService oss = ossStorageService
                .orElseThrow(() -> new BusinessException(503, "对象存储未启用，请配置 aliyun.oss 后重启"));
        // 先查归属：不存在的药品直接 404，避免先传 OSS 再发现药品不存在留下孤儿图
        if (medicineMapper.selectById(id) == null) {
            throw new BusinessException(404, "药品不存在");
        }
        String url = oss.upload("medicine", file);
        if (medicineMapper.updateImageUrl(id, url) == 0) {
            throw new BusinessException(404, "药品不存在");
        }
        adminAuditService.record(operatorId, "MEDICINE_IMAGE", "MEDICINE", id, "url=" + url);
        return Result.success(url);
    }

    /** 服务侧二次校验（纵深防御）：网关已校验，这里兜住路由配错/内网直连 */
    private void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }
}
