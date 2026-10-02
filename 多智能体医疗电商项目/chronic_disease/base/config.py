"""配置读取：把项目根目录的 config.ini 解析成一组带默认值的属性。

    设计要点：
      · 每一项都给了 fallback，配置文件缺段/缺项时不会抛异常，服务照常起得来；
      · 属性按配置文件的段分组（mysql / redis / postgres / milvus / llm / retrieval / app / logger），
        段名与属性前缀一一对应，便于对照 config.ini 排查；
      · 密钥类配置（DASHSCOPE_API_KEY）优先取环境变量，避免真实密钥落进配置文件；
      · 相对路径（配置文件本身、日志文件）一律相对项目根目录解析，
        因此不受启动时工作目录影响。

    典型用法：Config() 读根 config.ini，Config("config.ini") 读模块子目录下的配置。
"""
import configparser
import json
import os
from pathlib import Path


class Config:
    """config.ini 的读取封装（实例属性即各配置项，见模块 docstring）"""

    def __init__(self, config_file=None):
        """config_file 为空时读项目根目录的 config.ini；相对路径按项目根目录解析"""
        # 项目根目录（base/ 的上一级），用于解析相对路径
        self.base_dir = Path(__file__).resolve().parent.parent
        config_path = Path(config_file) if config_file else self.base_dir / 'config.ini'
        if not config_path.is_absolute():
            config_path = self.base_dir / config_path
        self.config = configparser.ConfigParser()
        # 读不到文件时不报错，后续取值全部走 fallback
        self.config.read(config_path, encoding='utf-8')

        # ---- [mysql] ----
        self.MYSQL_HOST = self.config.get('mysql', 'host', fallback='localhost')
        self.MYSQL_PORT = self.config.getint('mysql', 'port', fallback=3307)
        self.MYSQL_USER = self.config.get('mysql', 'user', fallback='root')
        self.MYSQL_PASSWORD = self.config.get('mysql', 'password', fallback='root')
        self.MYSQL_DATABASE = self.config.get('mysql', 'database', fallback='chronic_disease')

        # ---- [redis] ----
        self.REDIS_HOST = self.config.get('redis', 'host', fallback='localhost')
        self.REDIS_PORT = self.config.getint('redis', 'port', fallback=6379)
        self.REDIS_PASSWORD = self.config.get('redis', 'password', fallback='1234')
        self.REDIS_DB = self.config.getint('redis', 'db', fallback=0)

        # ---- [postgres] ----
        # 会话持久化用（见 core/chat_session.py）；连不上时该模块自动降级为内存存储
        self.PG_HOST = self.config.get('postgres', 'host', fallback='192.168.100.128')
        self.PG_PORT = self.config.getint('postgres', 'port', fallback=5432)
        self.PG_USER = self.config.get('postgres', 'user', fallback='postgres')
        self.PG_PASSWORD = self.config.get('postgres', 'password', fallback='1234')
        self.PG_DATABASE = self.config.get('postgres', 'database', fallback='chronic_disease')
        self.PG_SESSION_TABLE = self.config.get('postgres', 'table', fallback='chronic_chat_session')

        # ---- [milvus] ----
        # 注意 database_name 必须显式传给 MilvusClient，否则会连到默认库而「查不到数据」
        self.MILVUS_HOST = self.config.get('milvus', 'host', fallback='localhost')
        self.MILVUS_PORT = self.config.get('milvus', 'port', fallback='19530')
        self.MILVUS_DATABASE_NAME = self.config.get('milvus', 'database_name', fallback='chronic_disease')
        self.MILVUS_COLLECTION_NAME = self.config.get('milvus', 'collection_name', fallback='chronic_disease')

        # ---- [nacos] 提示词热更新（可选）----
        # 不配置 server_addr 即整段关闭：所有提示词走 core/prompts.py 内置默认，零行为变化。
        # 账号口令与 Java 侧用的是同一个 Nacos 控制台账号；口令不落盘到仓库（config.ini 不入库）。
        # 注意 configparser 的 get(fallback=) 只兜「键缺失」不兜「段缺失」，
        # 老配置文件里没有这一段时必须走空 dict，否则 Config() 初始化直接 NoSectionError。
        nacos = self.config['nacos'] if self.config.has_section('nacos') else {}
        self.NACOS_SERVER_ADDR = nacos.get('server_addr', '')
        self.NACOS_NAMESPACE = nacos.get('namespace', '')
        self.NACOS_USERNAME = nacos.get('username', '')
        self.NACOS_PASSWORD = nacos.get('password', '')
        self.NACOS_PROMPT_DATA_ID = nacos.get('prompt_data_id', 'chronic-ai-prompts.yaml')
        self.NACOS_PROMPT_GROUP = nacos.get('prompt_group', 'DEFAULT_GROUP')
        self.NACOS_PROMPT_REFRESH = nacos.get('refresh_interval', 30)

        # ---- [llm] ----
        self.LLM_MODEL = self.config.get('llm', 'model', fallback='qwen-plus')
        # 密钥优先取环境变量，代码与配置文件里都不落盘真实密钥
        self.DASHSCOPE_API_KEY = os.environ.get(
            'DASHSCOPE_API_KEY',
            self.config.get('llm', 'dashscope_api_key', fallback=''))
        self.DASHSCOPE_BASE_URL = self.config.get('llm', 'dashscope_base_url',
                                                  fallback='https://dashscope.aliyuncs.com/compatible-mode/v1')

        # ---- [retrieval] ----
        # 文档分块的父子块尺寸与检索条数（供入库脚本与检索层使用）
        self.PARENT_CHUNK_SIZE = self.config.getint('retrieval', 'parent_chunk_size', fallback=1000)
        self.CHILD_CHUNK_SIZE = self.config.getint('retrieval', 'child_chunk_size', fallback=200)
        self.CHUNK_OVERLAP = self.config.getint('retrieval', 'chunk_overlap', fallback=30)
        self.RETRIEVAL_K = self.config.getint('retrieval', 'retrieval_k', fallback=5)
        self.CANDIDATE_M = self.config.getint('retrieval', 'candidate_m', fallback=2)

        # ---- [app] ----
        # 用 json.loads 而非 eval 解析来源列表，避免配置内容被执行成代码
        try:
            self.VALID_SOURCES = json.loads(
                self.config.get('app', 'valid_sources',
                                fallback='["ai", "java", "test", "ops", "bigdata"]'))
        except (ValueError, TypeError):
            self.VALID_SOURCES = ["ai", "java", "test", "ops", "bigdata"]
        self.CUSTOMER_SERVICE_PHONE = self.config.get('app', 'customer_service_phone', fallback='12345678')

        # ---- [logger] ----
        # 日志路径同样按项目根目录解析，保证从任意工作目录启动都写进同一个文件
        log_file = self.config.get('logger', 'log_file', fallback='logs/app.log')
        log_path = Path(log_file)
        if not log_path.is_absolute():
            log_path = self.base_dir / log_path
        self.LOG_FILE = str(log_path)


if __name__ == '__main__':
    # 自检：直接运行本文件可打印一项配置，确认 config.ini 被正确读到
    conf = Config()
    print(conf.CHILD_CHUNK_SIZE)
