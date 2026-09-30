package com.chronic.shop.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chronic.common.exception.BusinessException;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.service.MedicineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 药品服务实现，提供库存扣减和恢复（原子操作 + 乐观锁防超卖）
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicineServiceImpl extends ServiceImpl<MedicineMapper, Medicine> implements MedicineService {

    @Override
    public boolean reduceStock(Long medicineId, Integer quantity) {
        // 原子扣减库存（UPDATE ... SET stock = stock - ? WHERE id = ? AND stock >= ?），防超卖
        int rows = baseMapper.reduceStock(medicineId, quantity);
        if (rows == 0) {
            Medicine medicine = getById(medicineId);
            if (medicine == null || medicine.getStatus() == 0) {
                throw new BusinessException("药品不存在或已下架");
            }
            throw new BusinessException("库存不足，当前库存: " + medicine.getStock());
        }
        return true;
    }

    @Override
    public boolean restoreStock(Long medicineId, Integer quantity) {
        // 订单取消时退还库存
        return baseMapper.restoreStock(medicineId, quantity) > 0;
    }
}