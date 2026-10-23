from dataclasses import dataclass

import httpx

from app.core.config import settings


class LLMNotConfigured(RuntimeError):
    pass


class LLMError(RuntimeError):
    pass


@dataclass
class LLMResult:
    content: str
    model: str


class DeepSeekService:
    """DeepSeek OpenAI-compatible client. Chapter pipeline will call chat() later."""

    async def chat(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float = 0.7,
        max_tokens: int = 4096,
    ) -> LLMResult:
        if not settings.deepseek_api_key:
            raise LLMNotConfigured("DEEPSEEK_API_KEY is empty")
        url = f"{settings.deepseek_base_url.rstrip('/')}/chat/completions"
        payload = {
            "model": settings.deepseek_model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": False,
        }
        headers = {
            "Authorization": f"Bearer {settings.deepseek_api_key}",
            "Content-Type": "application/json",
        }
        try:
            async with httpx.AsyncClient(timeout=settings.deepseek_timeout_seconds) as client:
                response = await client.post(url, json=payload, headers=headers)
                response.raise_for_status()
                data = response.json()
        except httpx.HTTPError as exc:
            raise LLMError(f"deepseek request failed: {exc}") from exc
        try:
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise LLMError("unexpected deepseek response") from exc
        return LLMResult(content=content, model=data.get("model", settings.deepseek_model))

    async def polish(self, text: str, instruction: str | None = None) -> LLMResult:
        system = (
            "你是中文小说编辑。请在不改变剧情、人物关系和事实信息的前提下润色文本："
            "修正错别字和病句，优化断句、节奏与用词，保持原文语气和叙事视角。"
            "只输出润色后的正文，不要解释。"
        )
        user = text if not instruction else f"额外要求：{instruction}\n\n原文：\n{text}"
        return await self.chat(
            [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ]
        )


deepseek = DeepSeekService()
