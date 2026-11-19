import json
import logging
import time

import requests
from django.conf import settings

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是中文小说正文清洗器，只做最小修复，不做改写。

第一步：判断本章是否完整。
- contaminated：前文在付费提示处截断，后文突然换成另一本书的人物/地点/时代/主线，或正文中途出现另一个章节标题；后文自成一个故事开头。
- suspicious：判断不确定，或只有弱信号。
- ok：正常场景切换、回忆、多线叙事、视角切换，不算拼接。
判定为 contaminated 或 suspicious 时，edits 必须为空，不得改写拼接内容。

第二步：仅当 verdict=ok 时，输出逐处补丁。
- 只修：广告/网址/公众号/QQ群/求票、乱码/零宽字符、被拆字、缺字、错字、断裂句。
- 对明显缺字的短句补最少字符，例如「跑很快」→「跑得很快」，「走进了屋」→「走进了屋子」；不要补写整段剧情。
- from 必须是该段原文中唯一出现的子串；to 是替换后的文本；删除时 to 为空串。
- 单处插入不超过 30 字；不改文风、不补剧情、不解释、不总结。
- 整段缺失不要编造，放进 unrecoverable。

只输出 JSON：
{"verdict":"ok|contaminated|suspicious","boundary":null,"reason":"","confidence":0.0,
 "edits":[{"p":0,"from":"","to":"","type":"ad|noise|typo|missing|other","conf":0.0}],
 "unrecoverable":[{"p":0,"reason":""}]}
"""


def resolve_model(model: str | None = None) -> str:
    return model or getattr(settings, "POLISH_MODEL", "deepseek-flash")


def build_messages(title: str, text: str) -> list[dict]:
    user = json.dumps(
        {"title": title, "paragraphs": text.split("\n")},
        ensure_ascii=False,
    )
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user},
    ]


def _strip_fence(content: str) -> str:
    content = content.strip()
    if content.startswith("```"):
        content = content.split("\n", 1)[-1]
        if content.endswith("```"):
            content = content[:-3]
    return content.strip()


def complete_json(system_prompt: str, user_content: str, model: str | None = None,
                  max_tokens: int = 2048) -> tuple[dict, dict, str]:
    api_key = getattr(settings, "DEEPSEEK_API_KEY", "")
    if not api_key:
        raise RuntimeError("DEEPSEEK_API_KEY 未配置")
    base = getattr(settings, "DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
    body = {
        "model": resolve_model(model),
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content},
        ],
        "temperature": 0.2,
        "max_tokens": max_tokens,
        "response_format": {"type": "json_object"},
    }
    last_error = None
    for attempt in range(3):
        try:
            resp = requests.post(
                f"{base}/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json=body,
                timeout=(10, 120),
            )
            if resp.status_code in (429, 500, 502, 503, 504):
                raise requests.HTTPError(f"HTTP {resp.status_code}", response=resp)
            resp.raise_for_status()
            data = resp.json()
            content = _strip_fence(data["choices"][0]["message"]["content"])
            return json.loads(content), data.get("usage") or {}, content
        except Exception as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(2**attempt)
    raise last_error


def clean_chapter(title: str, text: str, model: str | None = None):
    user = json.dumps(
        {"title": title, "paragraphs": text.split("\n")},
        ensure_ascii=False,
    )
    return complete_json(SYSTEM_PROMPT, user, model, max_tokens=4096)
