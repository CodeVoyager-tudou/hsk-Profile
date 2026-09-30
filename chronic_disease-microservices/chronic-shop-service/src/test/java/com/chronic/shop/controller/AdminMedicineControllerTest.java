package com.chronic.shop.controller;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.oss.OssStorageService;
import com.chronic.shop.dto.MedicineUpdateRequest;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.mapper.SeckillActivityMapper;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.service.AdminAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端「编辑药品」的契约：null=不改、脏值必须挡在写库之前、审计要记清"改了什么"。
 *
 * <p>这些校验不能只靠前端——接口可被脚本直接调用，而价格/积分为负或名称为空一旦落库，
 * 商城页与 AI 报价（现金价 + 积分兑换价两条渠道）都会跟着错。</p>
 *
 * @author chronic
 */
@ExtendWith(MockitoExtension.class)
class AdminMedicineControllerTest {

    private static final String ADMIN = "ADMIN";

    @Mock
    private MedicineMapper medicineMapper;

    @Mock
    private ShopOrderMapper shopOrderMapper;

    @Mock
    private SeckillActivityMapper seckillActivityMapper;

    @Mock
    private AdminAuditService adminAuditService;

    private AdminMedicineController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminMedicineController(medicineMapper, shopOrderMapper, seckillActivityMapper,
                Optional.<OssStorageService>empty(), adminAuditService);
    }

    private Medicine existing() {
        Medicine medicine = new Medicine();
        medicine.setId(12L);
        medicine.setName("阿司匹林肠溶片");
        medicine.setGenericName("阿司匹林");
        medicine.setCategory("慢病用药");
        medicine.setIndication("用于降低心肌梗死等血栓事件风险");
        medicine.setDosage("口服,一次100mg,一日1次");
        medicine.setPrice(new BigDecimal("12.00"));
        medicine.setStock(999);
        medicine.setPointsPrice(900);
        medicine.setPointsReward(12);
        medicine.setManufacturer("拜耳医药");
        medicine.setStatus(1);
        return medicine;
    }

    private MedicineUpdateRequest request() {
        return new MedicineUpdateRequest();
    }

    @Test
    void update_shouldRejectNonAdmin() {
        MedicineUpdateRequest req = request();
        req.setStock(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(12L, req, 1L, "USER"));
        assertTrue(ex.getMessage().contains("需要管理员权限"), ex.getMessage());
        verify(medicineMapper, never()).updateEditable(anyLong(), any());
    }

    @Test
    void update_shouldRejectEmptyRequest() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(12L, request(), 1L, ADMIN));
        assertTrue(ex.getMessage().contains("没有要修改的字段"), ex.getMessage());
        verify(medicineMapper, never()).updateEditable(anyLong(), any());
    }

    @Test
    void update_shouldReturn404WhenMedicineMissing() {
        when(medicineMapper.selectById(404L)).thenReturn(null);

        MedicineUpdateRequest req = request();
        req.setStock(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(404L, req, 1L, ADMIN));
        assertEquals(404, ex.getCode());
    }

    @Test
    void update_shouldRejectInvalidValuesBeforeWriting() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());

        MedicineUpdateRequest zeroPrice = request();
        zeroPrice.setPrice(BigDecimal.ZERO);
        assertThrows(BusinessException.class, () -> controller.update(12L, zeroPrice, 1L, ADMIN));

        MedicineUpdateRequest negativeStock = request();
        negativeStock.setStock(-1);
        assertThrows(BusinessException.class, () -> controller.update(12L, negativeStock, 1L, ADMIN));

        MedicineUpdateRequest negativePoints = request();
        negativePoints.setPointsPrice(-5);
        assertThrows(BusinessException.class, () -> controller.update(12L, negativePoints, 1L, ADMIN));

        MedicineUpdateRequest blankName = request();
        blankName.setName("   ");
        assertThrows(BusinessException.class, () -> controller.update(12L, blankName, 1L, ADMIN));

        // DECIMAL(10,2) 装不下三位小数：与其让 MySQL 静默四舍五入（对账对不上），不如直接拒绝
        MedicineUpdateRequest tooPrecise = request();
        tooPrecise.setPrice(new BigDecimal("12.345"));
        assertThrows(BusinessException.class, () -> controller.update(12L, tooPrecise, 1L, ADMIN));

        verify(medicineMapper, never()).updateEditable(anyLong(), any());
    }

    @Test
    void update_shouldRejectTooLongText() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());

        MedicineUpdateRequest req = request();
        req.setName("药".repeat(101));
        assertThrows(BusinessException.class, () -> controller.update(12L, req, 1L, ADMIN));

        MedicineUpdateRequest longIndication = request();
        longIndication.setIndication("症".repeat(2001));
        assertThrows(BusinessException.class, () -> controller.update(12L, longIndication, 1L, ADMIN));
    }

    @Test
    void update_shouldWriteOnlyProvidedFields_andAuditTheDiff() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());
        when(medicineMapper.updateEditable(eq(12L), any())).thenReturn(1);

        MedicineUpdateRequest req = request();
        req.setPrice(new BigDecimal("13.50"));
        req.setStock(1200);
        // 名称/描述类字段没传 → patch 里必须保持 null，否则会把已有内容清空
        controller.update(12L, req, 7L, ADMIN);

        ArgumentCaptor<Medicine> patch = ArgumentCaptor.forClass(Medicine.class);
        verify(medicineMapper).updateEditable(eq(12L), patch.capture());
        assertEquals(new BigDecimal("13.50"), patch.getValue().getPrice());
        assertEquals(1200, patch.getValue().getStock());
        assertNull(patch.getValue().getName(), "未提交的字段不该进入 patch（否则会被写成 null）");
        assertNull(patch.getValue().getIndication());
        assertNull(patch.getValue().getStatus(), "上下架有独立接口，编辑不该顺手改状态");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(adminAuditService).record(eq(7L), eq("MEDICINE_UPDATE"), eq("MEDICINE"), eq(12L), detail.capture());
        assertTrue(detail.getValue().contains("changed=[price, stock]"), detail.getValue());
        assertTrue(detail.getValue().contains("price: 12.00→13.50"), detail.getValue());
        assertTrue(detail.getValue().contains("stock: 999→1200"), detail.getValue());
    }

    @Test
    void update_shouldSupportClearingOptionalText() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());
        when(medicineMapper.updateEditable(eq(12L), any())).thenReturn(1);

        MedicineUpdateRequest req = request();
        req.setIndication("");     // 空串 = 显式清空
        controller.update(12L, req, 7L, ADMIN);

        ArgumentCaptor<Medicine> patch = ArgumentCaptor.forClass(Medicine.class);
        verify(medicineMapper).updateEditable(eq(12L), patch.capture());
        assertEquals("", patch.getValue().getIndication());
    }

    @Test
    void update_shouldTrimTextBeforeWriting() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());
        when(medicineMapper.updateEditable(eq(12L), any())).thenReturn(1);

        MedicineUpdateRequest req = request();
        req.setManufacturer("  石药集团  ");
        controller.update(12L, req, 7L, ADMIN);

        ArgumentCaptor<Medicine> patch = ArgumentCaptor.forClass(Medicine.class);
        verify(medicineMapper).updateEditable(eq(12L), patch.capture());
        assertEquals("石药集团", patch.getValue().getManufacturer());
    }

    @Test
    void update_shouldSkipWriteAndAudit_whenSubmittedValuesEqualCurrent() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());

        MedicineUpdateRequest req = request();
        req.setStock(999);                     // 与库里一致
        req.setPrice(new BigDecimal("12.00")); // 与库里一致（值相等，scale 不同也算未变）
        assertTrue(controller.update(12L, req, 7L, ADMIN).getData());
        verify(medicineMapper, never()).updateEditable(anyLong(), any());
        verify(adminAuditService, never()).record(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void update_shouldTreatDifferentScaleAsSamePrice() {
        // 界面把 12.00 显示成 "12" 再提交回来是常态。BigDecimal.equals 连 scale 一起比，
        // 用它就会判成"改价了"：白写一次库，审计里还多一条根本没发生的 "price: 12.00→12"（实测踩到）
        when(medicineMapper.selectById(12L)).thenReturn(existing());

        MedicineUpdateRequest req = request();
        req.setPrice(new BigDecimal("12.0"));
        assertTrue(controller.update(12L, req, 7L, ADMIN).getData());

        verify(medicineMapper, never()).updateEditable(anyLong(), any());
        verify(adminAuditService, never()).record(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void update_shouldAuditRealPriceChange_evenWhenScaleDiffers() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());
        when(medicineMapper.updateEditable(eq(12L), any())).thenReturn(1);

        MedicineUpdateRequest req = request();
        req.setPrice(new BigDecimal("12.5"));   // 真的变了（不只是 scale 差异）
        controller.update(12L, req, 7L, ADMIN);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(adminAuditService).record(eq(7L), eq("MEDICINE_UPDATE"), eq("MEDICINE"), eq(12L), detail.capture());
        assertTrue(detail.getValue().contains("changed=[price]"), detail.getValue());
        assertTrue(detail.getValue().contains("price: 12.00→12.5"), detail.getValue());
    }

    // ===== 新增 =====

    @Test
    void create_shouldRejectNonAdmin() {
        MedicineUpdateRequest req = request();
        req.setName("测试药");
        req.setPrice(BigDecimal.ONE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(req, 1L, "USER"));
        assertTrue(ex.getMessage().contains("需要管理员权限"), ex.getMessage());
        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    @Test
    void create_shouldRequireNameAndPrice() {
        MedicineUpdateRequest noName = request();
        noName.setPrice(BigDecimal.ONE);
        assertThrows(BusinessException.class, () -> controller.create(noName, 1L, ADMIN));

        MedicineUpdateRequest noPrice = request();
        noPrice.setName("测试药");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(noPrice, 1L, ADMIN));
        assertEquals("现金价必填", ex.getMessage());
        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    @Test
    void create_shouldDefaultStockAndPointsToZero_andStartOnSale() {
        MedicineUpdateRequest req = request();
        req.setName("  测试药  ");
        req.setPrice(new BigDecimal("19.90"));
        // 库存/积分都不传

        Medicine created = controller.create(req, 7L, ADMIN).getData();

        ArgumentCaptor<Medicine> saved = ArgumentCaptor.forClass(Medicine.class);
        verify(medicineMapper).insert(saved.capture());
        assertEquals("测试药", saved.getValue().getName(), "名称应 trim");
        assertEquals(new BigDecimal("19.90"), saved.getValue().getPrice());
        assertEquals(0, saved.getValue().getStock());
        assertEquals(0, saved.getValue().getPointsPrice());
        assertEquals(0, saved.getValue().getPointsReward());
        assertEquals(1, saved.getValue().getStatus(), "新建即上架");
        assertEquals(created, saved.getValue());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(adminAuditService).record(eq(7L), eq("MEDICINE_CREATE"), eq("MEDICINE"), any(), detail.capture());
        assertTrue(detail.getValue().contains("name=测试药"), detail.getValue());
    }

    @Test
    void create_shouldRejectInvalidValuesBeforeInsert() {
        MedicineUpdateRequest negative = request();
        negative.setName("测试药");
        negative.setPrice(new BigDecimal("9.90"));
        negative.setStock(-5);
        assertThrows(BusinessException.class, () -> controller.create(negative, 1L, ADMIN));

        MedicineUpdateRequest tooLongName = request();
        tooLongName.setName("药".repeat(101));
        tooLongName.setPrice(BigDecimal.ONE);
        assertThrows(BusinessException.class, () -> controller.create(tooLongName, 1L, ADMIN));

        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    // ===== 删除（护栏是重点） =====
    // 注意删除的顺序契约（复核 P1-6 之后）：**先 deleteById 拿行锁，再数引用，有引用就抛异常回滚**。
    // 所以护栏用例里 deleteById 也必须返回 1 —— 删除动作真的执行过，靠事务回滚撤销。
    // （先删后验是为了闭合"查引用 → 再删"之间的并发窗口：并发下单会更新 medicine.stock，因而阻塞在同一行锁上。）

    @Test
    void delete_shouldReject_whenOrdersReferenceTheMedicine() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());
        when(medicineMapper.deleteById(12L)).thenReturn(1);
        when(shopOrderMapper.selectCount(any())).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.delete(12L, 7L, ADMIN));
        assertTrue(ex.getMessage().contains("3 笔历史订单"), ex.getMessage());
        assertTrue(ex.getMessage().contains("下架"), "要引导管理员改用下架: " + ex.getMessage());
        // 有引用时不得写审计、不得清图（事务会回滚，药品行仍在）
        verify(adminAuditService, never()).record(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void delete_shouldReject_whenSeckillActivityReferencesTheMedicine() {
        when(medicineMapper.selectById(12L)).thenReturn(existing());
        when(medicineMapper.deleteById(12L)).thenReturn(1);
        when(shopOrderMapper.selectCount(any())).thenReturn(0L);
        when(seckillActivityMapper.selectCount(any())).thenReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.delete(12L, 7L, ADMIN));
        assertTrue(ex.getMessage().contains("秒杀活动"), ex.getMessage());
    }

    @Test
    void delete_shouldReturn404_whenMedicineMissing() {
        when(medicineMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.delete(404L, 7L, ADMIN));
        assertEquals(404, ex.getCode());
        verify(medicineMapper, never()).deleteById(anyLong());
    }

    @Test
    void delete_shouldReturn404_whenRowVanishedBetweenSelectAndDelete() {
        // 先删后验之后新增的分支：selectById 看得到、deleteById 影响 0 行（并发下已被别人删掉）
        when(medicineMapper.selectById(12L)).thenReturn(existing());
        when(medicineMapper.deleteById(12L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.delete(12L, 7L, ADMIN));
        assertEquals(404, ex.getCode());
        verify(shopOrderMapper, never()).selectCount(any());
    }

    @Test
    void delete_shouldRemoveRowAndAuditSnapshot_whenNothingReferencesIt() {
        Medicine medicine = existing();
        medicine.setImageUrl(null);          // 本例不涉及图片清理
        when(medicineMapper.selectById(12L)).thenReturn(medicine);
        when(medicineMapper.deleteById(12L)).thenReturn(1);
        when(shopOrderMapper.selectCount(any())).thenReturn(0L);
        when(seckillActivityMapper.selectCount(any())).thenReturn(0L);

        assertTrue(controller.delete(12L, 7L, ADMIN).getData());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(adminAuditService).record(eq(7L), eq("MEDICINE_DELETE"), eq("MEDICINE"), eq(12L), detail.capture());
        // 审计要留下"删掉的是什么"的快照，否则事后只看到一个 id
        assertTrue(detail.getValue().contains("name=阿司匹林肠溶片"), detail.getValue());
        assertTrue(detail.getValue().contains("price=12.00"), detail.getValue());
    }

    @Test
    void delete_shouldAlsoDeleteItsImage_whenNoOtherMedicineUsesIt() {
        OssStorageService oss = mock(OssStorageService.class);
        controller = new AdminMedicineController(medicineMapper, shopOrderMapper, seckillActivityMapper,
                Optional.of(oss), adminAuditService);
        Medicine medicine = existing();
        medicine.setImageUrl("https://qk-parent-xcu.oss-cn-hangzhou.aliyuncs.com/medicine/202609/med-12.jpg");
        when(medicineMapper.selectById(12L)).thenReturn(medicine);
        when(medicineMapper.deleteById(12L)).thenReturn(1);
        when(shopOrderMapper.selectCount(any())).thenReturn(0L);
        when(seckillActivityMapper.selectCount(any())).thenReturn(0L);
        when(medicineMapper.selectCount(any())).thenReturn(0L);   // 没有别的药品用同一张图

        controller.delete(12L, 7L, ADMIN);

        verify(oss).deleteByUrl(medicine.getImageUrl());
    }

    @Test
    void delete_shouldKeepImage_whenAnotherMedicineStillUsesIt() {
        OssStorageService oss = mock(OssStorageService.class);
        controller = new AdminMedicineController(medicineMapper, shopOrderMapper, seckillActivityMapper,
                Optional.of(oss), adminAuditService);
        Medicine medicine = existing();
        medicine.setImageUrl("https://qk-parent-xcu.oss-cn-hangzhou.aliyuncs.com/medicine/202609/shared.jpg");
        when(medicineMapper.selectById(12L)).thenReturn(medicine);
        when(medicineMapper.deleteById(12L)).thenReturn(1);
        when(shopOrderMapper.selectCount(any())).thenReturn(0L);
        when(seckillActivityMapper.selectCount(any())).thenReturn(0L);
        when(medicineMapper.selectCount(any())).thenReturn(1L);   // 还有别人在用

        controller.delete(12L, 7L, ADMIN);

        verify(oss, never()).deleteByUrl(anyString());
    }

    // ===== 新增的同名软防重（复核 P2-5） =====

    @Test
    void create_shouldRejectDuplicateNameAndManufacturer() {
        Medicine same = existing();          // 阿司匹林肠溶片 / 拜耳医药
        when(medicineMapper.selectList(any())).thenReturn(java.util.Collections.singletonList(same));

        MedicineUpdateRequest req = request();
        req.setName("阿司匹林肠溶片");
        req.setPrice(new BigDecimal("9.90"));
        req.setManufacturer("拜耳医药");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(req, 7L, ADMIN));
        assertTrue(ex.getMessage().contains("已存在同名药品"), ex.getMessage());
        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    @Test
    void create_shouldAllowSameNameWithDifferentManufacturer() {
        Medicine same = existing();
        when(medicineMapper.selectList(any())).thenReturn(java.util.Collections.singletonList(same));

        MedicineUpdateRequest req = request();
        req.setName("阿司匹林肠溶片");
        req.setPrice(new BigDecimal("9.90"));
        req.setManufacturer("其它厂家");     // 同名不同厂家属正常录入

        Medicine created = controller.create(req, 7L, ADMIN).getData();
        assertEquals("阿司匹林肠溶片", created.getName());
        verify(medicineMapper).insert(any(Medicine.class));
    }
}
