import json
import logging
import re

from apps.polish.align import aggregate_candidates, diff_ops, frequency_flags
from apps.polish.deepseek import complete_json
from apps.polish.models import PolishAlignSample, PolishSourceProfile
from apps.polish.profile_apply import apply_profile

logger = logging.getLogger(__name__)

PROFILE_SYSTEM = """你是中文书源特征分析器。给你候选换字/删字统计、单源字频信号和少量样本，
只输出一份最小归一化档案 JSON，不要改写正文，不要解释。
frequency_flags 是弱证据：只有与对齐候选或明显语法断裂一致时，才允许写进 missing_chars/context_rules。
格式：
{"version":1,"confidence":0.0,"missing_chars":[],"char_map":{"乾":{"to":"干","exceptions":["乾坤","乾隆"]}},
 "context_rules":[{"pattern":"","replacement":"","examples":[],"confidence":0.0}],
 "antonym_pairs":[{"from":"","to":"","direction":"a2b","confidence":0.0}]}
规则必须能对应到候选统计；不确定的不要写；反义对只在统计一致时写。
context_rules 尽量写成可泛化正则，examples 给至少两个不同例子；不能泛化时宁可只写 missing_chars/char_map，不要写死一个句子。"""


def _norm_title(title: str) -> str:
    return re.sub(r"\s|第[一二三四五六七八九十百千万零〇\d]+[章节卷回]", "", title or "")


def _pairs_for_source(source_url: str) -> list[tuple[str, str]]:
    targets = list(PolishAlignSample.objects.filter(source_url=source_url))
    pairs = []
    for sample in targets:
        others = list(
            PolishAlignSample.objects.filter(
                book_key=sample.book_key, chapter_index=sample.chapter_index
            ).exclude(source_url=source_url)
        )
        if not others:
            title = _norm_title(sample.chapter_title)
            if title:
                candidates = PolishAlignSample.objects.filter(
                    book_key=sample.book_key
                ).exclude(source_url=source_url)
                others = [
                    o for o in candidates if _norm_title(o.chapter_title) == title
                ]
        for other in others:
            pairs.append((sample.text, other.text))
    return pairs


def validate_profile(profile: dict, pairs: list[tuple[str, str]]) -> tuple[float, float]:
    if not profile or not pairs:
        return 0.0, 0.0
    unsupported = total_changes = restored = 0
    for a, b in pairs:
        out, changes = apply_profile(a, profile)
        total_changes += sum(c.get("count", 0) for c in changes)
        ops = diff_ops(a, b)
        restored += sum(1 for op in ops if op.get("b") and op["b"] in out)
        for change in changes:
            src = change.get("from")
            if src and not any(src in op.get("a", "") for op in ops):
                unsupported += change.get("count", 0)
    rate = unsupported / total_changes if total_changes else 0.0
    gain = min(1.0, restored / total_changes) if total_changes else 0.0
    return round(rate, 4), round(gain, 4)


def build_draft(source_url: str) -> PolishSourceProfile:
    pairs = _pairs_for_source(source_url)
    candidates = aggregate_candidates(pairs)
    samples = list(
        PolishAlignSample.objects.filter(source_url=source_url).values_list(
            "chapter_title", "text"
        )[:5]
    )
    frequency = frequency_flags([x for _, x in samples])
    user = json.dumps(
        {
            "candidates": candidates,
            "frequency_flags": frequency,
            "samples": [{"title": t, "text": x[:800]} for t, x in samples],
        },
        ensure_ascii=False,
    )
    payload, _, _ = complete_json(PROFILE_SYSTEM, user, max_tokens=2048)
    profile_json = {
        "version": 1,
        "confidence": float(payload.get("confidence", 0) or 0),
        "missing_chars": payload.get("missing_chars") or [],
        "char_map": payload.get("char_map") or {},
        "context_rules": payload.get("context_rules") or [],
        "antonym_pairs": payload.get("antonym_pairs") or [],
        "evidence": {
            "aligned_chapters": len(pairs),
            "frequency_flags": frequency,
            "candidates": candidates,
        },
    }
    for rule in profile_json["context_rules"]:
        try:
            re.compile(rule.get("pattern", ""))
        except re.error as exc:
            raise ValueError(f"LLM 返回了非法正则: {rule}") from exc

    freq_chars = {f["char"] for f in frequency}
    profile_json["char_map"] = {
        k: v
        for k, v in profile_json["char_map"].items()
        if (k in candidates["char_map"] and candidates["char_map"][k]["ratio"] >= 0.8)
        or k in freq_chars
    }
    profile_json["missing_chars"] = [
        c
        for c in profile_json["missing_chars"]
        if (
            c in candidates["missing_chars"]
            and candidates["missing_chars"][c]["ratio"] >= 0.8
        )
        or c in freq_chars
    ]
    profile_json["antonym_pairs"] = [
        p
        for p in profile_json["antonym_pairs"]
        if float(p.get("confidence", 0)) >= 0.9
        and candidates["char_map"].get(p.get("from"), {}).get("ratio", 0) >= 0.9
    ]

    rate, gain = validate_profile(profile_json, pairs)
    current = PolishSourceProfile.objects.filter(source_url=source_url).first()
    obj, _ = PolishSourceProfile.objects.update_or_create(
        source_url=source_url,
        defaults={
            "version": (current.version if current else 0) + 1,
            "status": "draft",
            "profile_json": profile_json,
            "confidence": profile_json["confidence"],
            "sample_count": PolishAlignSample.objects.filter(
                source_url=source_url
            ).count(),
            "holdout_false_change_rate": rate,
            "review_note": f"coverage_gain={gain}",
        },
    )
    return obj


def approve(source_url: str, reviewer: str, note: str = "") -> PolishSourceProfile:
    obj = PolishSourceProfile.objects.get(source_url=source_url)
    if obj.holdout_false_change_rate >= 0.01:
        raise ValueError("误改率 >= 1%，拒绝启用")
    obj.status = "verified"
    obj.reviewer = reviewer
    obj.review_note = note or obj.review_note
    obj.save(update_fields=["status", "reviewer", "review_note", "update_time"])
    return obj
