"""AI经验萃取平台 - 文件解析服务

负责上传文件的内容提取（txt/md/docx/pdf/pptx/xlsx/html/image/audio）。

Author: AI Extract Team
Since: 2026-06-29
"""

import logging
from contextlib import asynccontextmanager
from typing import Dict

from fastapi import FastAPI

from routers.internal import router as internal_router

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动/关闭生命周期管理"""
    logger.info("文件解析服务正在启动...")
    yield
    logger.info("文件解析服务正在关闭...")


app = FastAPI(
    title="AI经验萃取平台 - 文件解析服务",
    description="文件内容提取：txt/md/docx/pdf/pptx/xlsx/image/audio",
    version="1.0.0",
    lifespan=lifespan,
)

app.include_router(internal_router)


@app.get("/")
async def root() -> Dict[str, str]:
    return {"service": "AI Extract File Parser", "status": "running"}


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "healthy", "service": "file-parser"}
