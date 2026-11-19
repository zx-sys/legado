import re
from dataclasses import dataclass, field

URL_RE = re.compile(
    r"(https?://|www\.|[a-zA-Z0-9-]+\.(com|cn|net|org|cc|info|me|top|xyz|vip))",
    re.IGNORECASE,
)


@dataclass
class ValidationResult:
    ok: bool
    verdict: str = ""
    edits: list[dict] = field(default_factory=list)
    reason: str = ""


def _paragraph_offsets(paragraphs: list[str]) -> list[int]:
    offsets = []
    total = 0
    for p in paragraphs:
        offsets.append(total)
        total += len(p) + 1
    return offsets


def validate_payload(
    raw_text: str,
    payload: dict,
    *,
    max_insert: int = 30,
    max_ratio: float = 0.05,
    max_edits: int = 40,
) -> ValidationResult:
    if not isinstance(payload, dict):
        return ValidationResult(False, reason="payload 不是对象")
    verdict = str(payload.get("verdict") or "").strip()
    if verdict not in ("ok", "contaminated", "suspicious"):
        return ValidationResult(False, reason=f"非法 verdict: {verdict}")

    try:
        confidence = float(payload.get("confidence") or 0)
    except (TypeError, ValueError):
        confidence = 0

    paragraphs = raw_text.split("\n")
    offsets = _paragraph_offsets(paragraphs)
    raw_edits = payload.get("edits") or []
    if not isinstance(raw_edits, list):
        return ValidationResult(False, reason="edits 不是数组")

    if verdict != "ok":
        return ValidationResult(
            True, verdict=verdict, edits=[], reason=str(payload.get("reason") or "")
        )

    if confidence < 0.7:
        return ValidationResult(
            True, verdict="suspicious", edits=[], reason="置信度过低"
        )

    if len(raw_edits) > max_edits:
        return ValidationResult(False, reason="编辑数量过多")

    edits = []
    total_delta = 0
    for item in raw_edits:
        if not isinstance(item, dict):
            return ValidationResult(False, reason="edit 不是对象")
        try:
            p = int(item.get("p"))
        except (TypeError, ValueError):
            return ValidationResult(False, reason="段号非法")
        if p < 0 or p >= len(paragraphs):
            return ValidationResult(False, reason=f"段号越界: {p}")
        frm = item.get("from")
        to = item.get("to")
        if not isinstance(frm, str) or not isinstance(to, str) or not frm:
            return ValidationResult(False, reason="from/to 非法")
        if to == frm:
            continue
        para = paragraphs[p]
        if para.count(frm) != 1:
            return ValidationResult(False, reason=f"锚点不唯一: p={p}")
        delta = len(to) - len(frm)
        if delta > max_insert:
            return ValidationResult(False, reason=f"单处插入过长: p={p}")
        if not URL_RE.search(frm) and URL_RE.search(to):
            return ValidationResult(False, reason=f"新增网址: p={p}")
        start = offsets[p] + para.index(frm)
        edits.append(
            {"p": p, "start": start, "end": start + len(frm), "from": frm, "replace": to}
        )
        # 删除纯广告/网址不计入总修改量，避免短章因长 URL 被误拒
        if not (URL_RE.search(frm) and not to.strip()):
            total_delta += abs(delta)

    if raw_text and total_delta / len(raw_text) > max_ratio:
        return ValidationResult(False, reason="总修改量过大")

    edits.sort(key=lambda e: e["start"])
    return ValidationResult(True, verdict="ok", edits=edits)
