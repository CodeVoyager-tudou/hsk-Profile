-- =============================================
-- edu_user 种子数据（联调账号 admin/123456，密码为 BCrypt 密文）
-- 由 Flyway 执行（spring.flyway.locations=classpath:db/migration）
-- 约定：所有 DDL 都用 IF NOT EXISTS / 信息模式守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

-- 不再写入手机号：V3 会移除该列，种子数据也不引用它（保证脚本在"删列之后重跑"仍然幂等）
INSERT INTO sys_user (id, username, password, nickname, gender, status)
VALUES (1, 'admin', '$2b$10$Lnk.VnBado5D.vhO7vo.buJS7rbk/IF110TL1q6YF2JO3THp9erLq', '系统管理员', 1, 1)
ON DUPLICATE KEY UPDATE id = id;
