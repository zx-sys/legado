import re


def _apply_char_map(text: str, src: str, dst: str, exceptions: list[str]) -> str:
    if not exceptions:
        return text.replace(src, dst)
    pattern = "(" + "|".join(map(re.escape, exceptions)) + ")"
    parts = re.split(pattern, text)
    return "".join(p if p in exceptions else p.replace(src, dst) for p in parts)


def _expand(replacement: str, match) -> str:
    def repl(m):
        try:
            return match.group(int(m.group(1)))
        except (IndexError, ValueError):
            return ""

    return re.sub(r"\$(\d)", repl, replacement)


def apply_profile(text: str, profile: dict) -> tuple[str, list[dict]]:
    if not profile:
        return text, []
    original = text
    changes = []

    for src, cfg in (profile.get("char_map") or {}).items():
        dst = cfg.get("to", "")
        if not src or not dst:
            continue
        new_text = _apply_char_map(text, src, dst, cfg.get("exceptions") or [])
        if new_text != text:
            changes.append(
                {"rule": "char_map", "from": src, "to": dst, "count": text.count(src)}
            )
            text = new_text

    for rule in sorted(
        profile.get("context_rules") or [],
        key=lambda r: -float(r.get("confidence", 0)),
    ):
        pattern = rule.get("pattern")
        replacement = rule.get("replacement")
        if not pattern or replacement is None:
            continue
        try:
            compiled = re.compile(pattern)
        except re.error:
            continue
        new_text, n = compiled.subn(lambda m: _expand(replacement, m), text)
        if n:
            changes.append({"rule": "context", "pattern": pattern, "count": n})
            text = new_text

    for pair in profile.get("antonym_pairs") or []:
        if float(pair.get("confidence", 0)) < 0.9:
            continue
        src, dst = pair.get("from"), pair.get("to")
        if not src or not dst:
            continue
        if src in text:
            changes.append(
                {"rule": "antonym", "from": src, "to": dst, "count": text.count(src)}
            )
            text = text.replace(src, dst)

    if text == original:
        return text, []
    return text, changes
