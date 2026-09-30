package com.chronic.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chronic.shop.entity.Medicine;

public interface MedicineService extends IService<Medicine> {

    /**
     * 原子扣减库存, 不足或已下架抛异常
     */
    boolean reduceStock(Long medicineId, Integer quantity);

    /**
     * 订单取消回补库存
     */
    boolean restoreStock(Long medicineId, Integer quantity);
}
