import os

from fastapi import FastAPI

# 预留：未来用于服务间鉴权的内部令牌。M1 阶段仅读取，暂不启用任何鉴权中间件。
INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN")

app = FastAPI(title="MealMind Agent", version="0.1.0")


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok", "service": "mealmind-agent"}
