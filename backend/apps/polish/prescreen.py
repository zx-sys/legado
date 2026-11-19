import re

PAYWALL_RE = re.compile(
    r"(本章未完|请充值|VIP章节|订阅本章|购买本章|金币不足|余额不足|充值后阅读|试读|付费章节)",
    re.IGNORECASE,
)
MID_HEADING_RE = re.compile(
    r"(?m)^\s*(第[一二三四五六七八九十百千万零〇\d]+章|Chapter\s+\d+)"
)


def _repeated_block(text: str, size: int = 200) -> bool:
    if len(text) < size * 2:
        return False
    seen = {}
    for i in range(0, len(text) - size + 1):
        h = hash(text[i : i + size])
        prev = seen.get(h)
        if prev is not None and i - prev >= size:
            return True
        seen[h] = i
    return False


def guess_boundary(raw_text: str) -> int | None:
    """模型没给 boundary 时，用中途章节标题/付费提示行推断拼接起点。"""
    length = len(raw_text)
    if length == 0:
        return None
    for m in MID_HEADING_RE.finditer(raw_text):
        if m.start() >= length * 0.10:
            return raw_text[: m.start()].count("\n")
    m = PAYWALL_RE.search(raw_text)
    if m:
        return raw_text[: m.end()].count("\n") + 1
    return None


def prescreen(raw_text: str, book_median_len: int = 0) -> list[str]:
    flags = []
    length = len(raw_text)
    if length == 0:
        return ["empty"]

    head = raw_text[: int(length * 0.85)]
    if PAYWALL_RE.search(head):
        flags.append("paywall")

    headings = list(MID_HEADING_RE.finditer(raw_text))
    mid = [
        m for m in headings if int(length * 0.10) <= m.start() <= int(length * 0.90)
    ]
    if mid:
        flags.append("mid_heading")

    if book_median_len and length > book_median_len * 3 and "mid_heading" in flags:
        flags.append("length_outlier")

    if _repeated_block(raw_text):
        flags.append("repeated_block")

    return flags
