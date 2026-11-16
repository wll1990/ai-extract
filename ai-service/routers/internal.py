"""AI服务 - 文件解析路由模块

提供内部REST接口供Java后端调用，处理上传文件的文本提取。
大文件解析通过 run_in_executor 放入线程池，不阻塞事件循环。
"""

import asyncio
import base64
import ipaddress
import logging
import os as _os
import socket
import tempfile
import urllib.request
from typing import Dict
from urllib.parse import urlparse

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

# 允许下载的域名后缀（逗号分隔，可用环境变量覆盖）；默认只允许 OSS
_ALLOWED_DOWNLOAD_HOSTS = tuple(
    h.strip().lower()
    for h in _os.getenv("ALLOWED_DOWNLOAD_HOSTS", "aliyuncs.com").split(",")
    if h.strip()
)
_DOWNLOAD_TIMEOUT_SECONDS = 30

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal", tags=["file-parser"])


class ParseFileRequest(BaseModel):
    """文件解析请求"""
    file_path: str
    file_name: str


@router.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "healthy", "service": "file-parser"}


@router.post("/parse-file")
async def parse_file(request: ParseFileRequest):
    """解析上传的文件为纯文本 — 支持 txt/md/docx/pdf/pptx/xlsx/csv/html/png/jpg/mp3/wav

    同步IO通过 run_in_executor 异步化，避免阻塞 uvicorn 事件循环。
    """
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _parse_sync, request.file_path, request.file_name)


def _assert_download_allowed(url: str) -> None:
    """只允许下载白名单域名，并拒绝解析到内网/回环地址的 URL（防 SSRF）。"""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise HTTPException(status_code=400, detail="仅支持 http(s) 文件地址")
    host = (parsed.hostname or "").lower()
    if not host or not any(
        host == suffix or host.endswith("." + suffix) for suffix in _ALLOWED_DOWNLOAD_HOSTS
    ):
        raise HTTPException(status_code=400, detail="文件地址域名不在允许范围")
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror as exc:
        raise HTTPException(status_code=400, detail="文件地址域名解析失败") from exc
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved:
            raise HTTPException(status_code=400, detail="文件地址指向内网，已拒绝")


def _parse_sync(file_path: str, file_name: str) -> dict:
    """同步文件解析逻辑，在线程池中运行。支持本地路径或 http(s) 预签名 URL。"""
    if file_path.startswith(("http://", "https://")):
        # OSS 预签名 URL：下载到临时文件再解析，解析后清理（backend 与 ai-service 可分离部署）
        _assert_download_allowed(file_path)
        suffix = _os.path.splitext(file_name)[1].lower() if file_name else ""
        tmp = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
        tmp_path = tmp.name
        tmp.close()
        try:
            with urllib.request.urlopen(file_path, timeout=_DOWNLOAD_TIMEOUT_SECONDS) as resp, \
                    open(tmp_path, "wb") as out:
                while True:
                    chunk = resp.read(1024 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
            return _parse_local(tmp_path, file_name)
        finally:
            if _os.path.exists(tmp_path):
                _os.unlink(tmp_path)
    return _parse_local(file_path, file_name)


def _parse_local(file_path: str, file_name: str) -> dict:
    """按本地文件路径解析（原逻辑）。"""
    if not _os.path.exists(file_path):
        raise HTTPException(status_code=404, detail=f"文件不存在: {file_path}")

    ext = _os.path.splitext(file_name)[1].lower() if file_name else ""

    try:
        # 纯文本
        if ext in (".txt", ".md"):
            with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                text = f.read()
            return {"text": text, "file_type": ext, "size": len(text)}

        # Word
        elif ext == ".docx":
            from docx import Document
            doc = Document(file_path)
            text = "\n".join(p.text for p in doc.paragraphs if p.text.strip())
            return {"text": text, "file_type": ext, "size": len(text)}

        # PDF
        elif ext == ".pdf":
            import PyPDF2
            reader = PyPDF2.PdfReader(file_path)
            text = "\n".join(p.extract_text() or "" for p in reader.pages)
            return {"text": text, "file_type": ext, "size": len(text)}

        # PowerPoint
        elif ext in (".ppt", ".pptx"):
            from pptx import Presentation
            prs = Presentation(file_path)
            slides = []
            for slide in prs.slides:
                for shape in slide.shapes:
                    if shape.has_text_frame:
                        slides.append(shape.text_frame.text)
            text = "\n".join(slides)
            return {"text": text, "file_type": ext, "size": len(text)}

        # Excel
        elif ext in (".xls", ".xlsx", ".csv"):
            import pandas as pd
            if ext == ".csv":
                df = pd.read_csv(file_path)
            else:
                df = pd.read_excel(file_path)
            text = df.to_string(index=False)
            return {"text": text, "file_type": ext, "size": len(text)}

        # HTML
        elif ext in (".html", ".htm"):
            from bs4 import BeautifulSoup
            with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                soup = BeautifulSoup(f.read(), "html.parser")
            text = soup.get_text(separator="\n")
            return {"text": text, "file_type": ext, "size": len(text)}

        # 图片
        elif ext in (".png", ".jpg", ".jpeg"):
            with open(file_path, "rb") as f:
                img_b64 = base64.b64encode(f.read()).decode()
            return {
                "text": f"[图片文件: {file_name}]\n请使用多模态模型（如 GPT-4V / Gemini）解析此图片内容。\nBase64: data:image/{ext[1:]};base64,{img_b64[:200]}...",
                "file_type": ext,
                "size": _os.path.getsize(file_path),
                "needs_manual": True,
            }

        # 音频
        elif ext in (".mp3", ".wav"):
            with open(file_path, "rb") as f:
                audio_b64 = base64.b64encode(f.read()).decode()
            return {
                "text": f"[音频文件: {file_name}]\n请使用语音转文字服务（如 Whisper / 飞书妙记）转写此音频。\nBase64: data:audio/{ext[1:]};base64,{audio_b64[:200]}...",
                "file_type": ext,
                "size": _os.path.getsize(file_path),
                "needs_manual": True,
            }

        else:
            raise HTTPException(status_code=400, detail=f"不支持的文件格式: {ext}")

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"文件解析失败: {file_path} ({file_name}): {e}")
        raise HTTPException(status_code=500, detail=f"文件解析失败: {e}")
