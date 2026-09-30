"""日志初始化：全项目共用一个 logger，同时输出到日志文件与控制台。

    使用方法：`from base.logger import logger`，然后 logger.info(...)。
    模块被导入时就完成初始化（见文件末尾的 logger = setup_logging()），
    各模块无需各自配置 handler，也避免重复添加 handler 导致日志重复打印。
"""
import logging
import os
from base.config import Config


def setup_logging(log_file=Config().LOG_FILE):
    """配置并返回全局 logger（幂等：重复调用不会重复挂 handler）。

    log_file 默认取 config.ini [logger] log_file；目录不存在时自动创建。
    """
    # 日志目录可能尚未创建（首次启动），先确保存在，否则 FileHandler 会直接报错
    os.makedirs(os.path.dirname(log_file), exist_ok=True)
    logger = logging.getLogger("chronic_disease")
    logger.setLevel(logging.INFO)
    # 只在尚未配置过 handler 时添加，避免模块被多次导入时日志打印多份
    if not logger.handlers:
        file_handler = logging.FileHandler(log_file, encoding='utf-8')
        file_handler.setLevel(logging.INFO)
        console_handler = logging.StreamHandler()
        console_handler.setLevel(logging.INFO)
        formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
        file_handler.setFormatter(formatter)
        console_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
        logger.addHandler(console_handler)
    return logger


# 初始化日志器（导入本模块即完成配置）
logger = setup_logging()
