"""阿里云百炼（DashScope）OpenAI 兼容端点的最小对话客户端。

只负责一次「要 JSON 的」对话补全：超时、重试与错误归一化；
提示词与结果校验留在调用方（理解器 / 表达器），便于各自降级。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any

import httpx

logger = logging.getLogger("mealmind.agent.llm")


class ModelError(RuntimeError):
    """模型调用失败（超时 / 网络 / 非 200 / 无 JSON），交由调用方降级。"""


@dataclass(frozen=True)
class LlmUsage:
    """一次模型调用的 token 用量（M4 W5 成本计量）。

    优先取响应里的真实 usage；缺失时按字符数估算（1 汉字≈1 token）并标记 estimated。
    """

    input_tokens: int
    output_tokens: int
    estimated: bool


@dataclass(frozen=True)
class LlmResult:
    """一次 JSON 补全的结果：解析后的对象 + 用量。

    客户端是模块级单例且可能多线程并发，usage 只能随返回值传递，不能挂实例。
    """

    data: dict[str, Any]
    usage: LlmUsage


class QwenClient:
    """百炼 OpenAI 兼容端点客户端；未配置密钥时 `configured` 为 False。"""

    def __init__(self, *, api_key: str, base_url: str, model: str,
                 timeout_s: float = 3.0, retries: int = 1,
                 enable_thinking: bool = False) -> None:
        self._api_key = api_key
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._timeout_s = timeout_s
        self._retries = retries
        # qwen3 系列默认开思考模式：本项目的理解/表达都是结构化短输出，
        # 关掉后实测延迟从 ~3.4s 降到 ~1.3s、输出 token 207 → 62
        self._enable_thinking = enable_thinking

    @property
    def configured(self) -> bool:
        return bool(self._api_key)

    def chat_json(self, system: str, user: str) -> LlmResult:
        """一次 JSON 输出补全；超时默认 3s、失败重试 1 次（技术方案 §6.4）。"""

        payload = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "enable_thinking": self._enable_thinking,
        }
        last_error: Exception | None = None
        for attempt in range(self._retries + 1):
            try:
                with httpx.Client(timeout=self._timeout_s) as client:
                    response = client.post(
                        f"{self._base_url}/chat/completions",
                        headers={"Authorization": f"Bearer {self._api_key}"},
                        json=payload,
                    )
                if response.status_code != 200:
                    raise ModelError(f"qwen returned {response.status_code}: {response.text[:200]}")
                body = response.json()
                content = body["choices"][0]["message"]["content"]
                data = _extract_json(content)
                return LlmResult(data=data, usage=_usage_of(body, system, user, content))
            except Exception as exc:  # 网络 / 超时 / 解析失败统一走降级
                last_error = exc
                logger.warning("qwen call failed (attempt %d/%d): %s",
                               attempt + 1, self._retries + 1, exc)
        raise ModelError(str(last_error))


def _usage_of(body: dict[str, Any], system: str, user: str, content: str) -> LlmUsage:
    """优先取响应 usage；缺失时按字符数估算（1 汉字≈1 token）。"""

    usage = body.get("usage") or {}
    input_tokens = usage.get("prompt_tokens")
    output_tokens = usage.get("completion_tokens")
    if isinstance(input_tokens, int) and isinstance(output_tokens, int):
        return LlmUsage(input_tokens=input_tokens, output_tokens=output_tokens, estimated=False)
    return LlmUsage(
        input_tokens=len(system) + len(user),
        output_tokens=len(content),
        estimated=True,
    )


def _extract_json(content: str) -> dict[str, Any]:
    """从回复里取出 JSON 对象；容忍 ```json 包裹与前后缀说明文字。"""

    text = content.strip()
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise ModelError("qwen returned no JSON object")
    return json.loads(text[start : end + 1])
