"""
PureSnap 云端动态规则轻量服务 (FastAPI 参考实现)
对应 PRD 第四章 4.1：「云端动态规则 + 客户端执行」混合架构
负责短链重定向追踪、签名参数清洗及动态下发最新正则与 CDN 节点配置，文件流由客户端直连平台 CDN 下载，后端零带宽转发成本。
"""

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="PureSnap Dynamic Rule Service", version="1.2.0")


class ParseRequest(BaseModel):
    share_text: str
    client_version: str = "1.2.0"


@app.get("/v1/rules")
async def get_dynamic_rules():
    return {
        "rule_version": "2026.10.26-r2",
        "xhs_cdn_host": "https://ci.xiaohongshu.com/",
        "xhs_lossless_suffix": "?imageView2/2/w/0/format/png",
        "douyin_video_rewrite": {"from": "playwm", "to": "play", "ratio": "1080p"},
        "zuiyou_image_field": "urls.origin",
    }
