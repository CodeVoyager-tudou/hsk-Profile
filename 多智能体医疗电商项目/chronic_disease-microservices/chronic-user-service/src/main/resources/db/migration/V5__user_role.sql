-- =============================================
-- 用户角色列（管理端权限底座）
--
-- 【为什么放签发而不是每次查库】
-- role 写进 JWT claims 后，网关对 /api/admin/** 的角色校验零查库成本；
-- 代价是改角色后要等令牌过期/重新登录才生效 —— 管理角色变更低频，可接受。
-- =============================================

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'role');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT ''USER'' COMMENT ''角色: USER-普通用户 ADMIN-管理员''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 种子管理员账号授予 ADMIN 角色（幂等：已是 ADMIN 则不动）
UPDATE sys_user SET role = 'ADMIN' WHERE username = 'admin' AND role <> 'ADMIN';
