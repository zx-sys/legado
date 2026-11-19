import difflib


def diff_ops(a: str, b: str) -> list[dict]:
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    ops = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        ops.append({"tag": tag, "a": a[i1:i2], "b": b[j1:j2]})
    return ops


def aggregate_candidates(pairs: list[tuple[str, str]]) -> dict:
    counts = {}
    totals = 0
    for a, b in pairs:
        totals += 1
        for op in diff_ops(a, b):
            src, dst = op["a"], op["b"]
            if op["tag"] == "replace" and len(src) == 1 and len(dst) == 1:
                key = (src, dst)
                counts[key] = counts.get(key, 0) + 1
            elif op["tag"] == "delete" and src:
                for ch in src:
                    counts[(ch, "")] = counts.get((ch, ""), 0) + 1
            elif op["tag"] == "insert" and dst:
                # 目标源缺少、其他源有的字
                for ch in dst:
                    counts[(ch, "")] = counts.get((ch, ""), 0) + 1
    char_map = {}
    missing = {}
    for (src, dst), n in counts.items():
        ratio = n / max(1, totals)
        if dst:
            char_map.setdefault(src, {"to": dst, "count": n, "ratio": round(ratio, 3)})
        else:
            missing[src] = {"count": n, "ratio": round(ratio, 3)}
    if totals < 3:
        return {"total_pairs": totals, "char_map": {}, "missing_chars": {}}
    return {"total_pairs": totals, "char_map": char_map, "missing_chars": missing}


COMMON_RATES = {
    "得": 0.0015,
    "花": 0.0006,
    "的": 0.04,
    "了": 0.012,
    "是": 0.008,
    "不": 0.006,
}


def frequency_flags(texts: list[str], min_expected: float = 5.0) -> list[dict]:
    """单源字频辅助信号：某常用字应有却几乎为 0 时标记，只作弱证据。"""
    total = sum(len(t) for t in texts)
    if total == 0:
        return []
    counts = {}
    for t in texts:
        for ch in t:
            counts[ch] = counts.get(ch, 0) + 1
    flags = []
    for ch, rate in COMMON_RATES.items():
        expected = total * rate
        observed = counts.get(ch, 0)
        if expected >= min_expected and observed < expected * 0.2:
            flags.append(
                {"char": ch, "observed": observed, "expected": round(expected, 1)}
            )
    return flags
