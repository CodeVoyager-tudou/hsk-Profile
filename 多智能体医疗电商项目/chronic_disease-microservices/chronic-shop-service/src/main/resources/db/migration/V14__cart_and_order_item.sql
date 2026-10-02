-- =============================================
-- 购物车 + 订单明细子表（购物车合并结算）
--
-- 为什么需要这两张表：
-- 1) cart_item：详情页"加入购物车"的落点。同一用户对同一药品重复加购时
--    由唯一键 uk_user_medicine 合并数量（结算、删除都按行主键操作，归属清晰）。
-- 2) shop_order_item：订单明细。此前 shop_order 直接冗余"单个药品"的字段
--   （medicine_id/quantity/unit_price），满减券门槛只能拿单商品小计去比——
--    "满 30 减 5"这种券，单买一件 12 元的药永远够不着。
--    有了明细表，购物车多件商品合成一笔订单（order_type='CART'），
--    total_amount 是跨商品合计，券门槛按合计判定，券就真正用得上了。
--
-- 兼容性约定：
--   · 存量订单不改数据：order_type 默认 'SINGLE'，明细表里没有旧行，
--     取消/关单退库存时"有明细按明细循环、无明细按旧的单商品字段"兜底，
--     秒杀单（直插 shop_order、无明细）走老路径，行为完全不变。
--   · CART 单的 medicine_id 置 NULL、unit_price 存 0、quantity 存总件数、
--     medicine_name 存摘要（"购物车结算(N件)"）——这些单商品字段对多商品单
--     已无业务含义，只作积分备注/搜索等场景的可读兜底。
-- 约定：DDL 用 information_schema 守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

-- 1) 购物车条目
CREATE TABLE IF NOT EXISTS cart_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    medicine_id BIGINT NOT NULL COMMENT '药品ID',
    quantity INT NOT NULL DEFAULT 1 COMMENT '数量(1~99)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_medicine (user_id, medicine_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车条目表';

-- 2) 订单明细子表（一笔订单 N 行；SINGLE 单也写一行，取消/关单逻辑统一按明细循环）
CREATE TABLE IF NOT EXISTS shop_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id BIGINT NOT NULL COMMENT '订单ID(shop_order.id)',
    medicine_id BIGINT NOT NULL COMMENT '药品ID',
    medicine_name VARCHAR(100) NOT NULL COMMENT '药品名称(下单时快照)',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '成交单价(下单时快照)',
    quantity INT NOT NULL COMMENT '数量',
    subtotal DECIMAL(10,2) NOT NULL COMMENT '小计 = 单价 x 数量',
    INDEX idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 3) shop_order 加订单类型：SINGLE-单商品直购(默认) CART-购物车合并结算
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND COLUMN_NAME = 'order_type');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE shop_order ADD COLUMN order_type VARCHAR(16) NOT NULL DEFAULT ''SINGLE'' COMMENT ''订单类型: SINGLE-单商品直购 CART-购物车合并结算'' AFTER user_id',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) medicine_id 放开 NOT NULL：CART 单的药品信息全部在明细表里，
--    主表的 medicine_id 无意义（置 NULL），不能被 NOT NULL 挡住
SET @col_nullable := (SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND COLUMN_NAME = 'medicine_id');
SET @ddl := IF(@col_nullable = 'NO',
    'ALTER TABLE shop_order MODIFY COLUMN medicine_id BIGINT NULL COMMENT ''药品ID(SINGLE单冗余；CART单NULL,药品看明细表shop_order_item)''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
