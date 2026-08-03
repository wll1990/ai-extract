"""AI Extract 文件解析服务 — 统一日志配置

提供 rotating file handler，格式对齐后端 Spring Boot logback：
  2026-07-30 19:30:15.123 INFO  [module] message

Author: AI Extract Team
Since: 2026-07-30
"""

import logging
import os
from logging.handlers import RotatingFileHandler

LOG_DIR = os.environ.get("LOG_DIR", os.path.join(os.path.dirname(os.path.dirname(__file__)), "logs"))
LOG_FMT = logging.Formatter(
    "%(asctime)s.%(msecs)03d %(levelname)-5s [%(name)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)


def setup_logging(name: str = "ai-service") -> logging.Logger:
    """配置根 logger，输出到控制台 + 轮转文件 + 错误文件。"""

    os.makedirs(LOG_DIR, exist_ok=True)

    root = logging.getLogger()
    root.setLevel(logging.INFO)

    # 控制台
    console = logging.StreamHandler()
    console.setLevel(logging.INFO)
    console.setFormatter(LOG_FMT)
    root.addHandler(console)

    # 文件轮转 — 全量日志（10MB × 5）
    all_file = RotatingFileHandler(
        os.path.join(LOG_DIR, "ai-service.log"),
        maxBytes=10 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8",
    )
    all_file.setLevel(logging.INFO)
    all_file.setFormatter(LOG_FMT)
    root.addHandler(all_file)

    # 文件轮转 — 错误日志（5MB × 5）
    err_file = RotatingFileHandler(
        os.path.join(LOG_DIR, "ai-service-error.log"),
        maxBytes=5 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8",
    )
    err_file.setLevel(logging.ERROR)
    err_file.setFormatter(LOG_FMT)
    root.addHandler(err_file)

    return logging.getLogger(name)
