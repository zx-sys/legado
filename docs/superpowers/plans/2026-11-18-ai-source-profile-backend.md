# 源级特征库（系统性缺字/换字）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从多用户上传的多源同章样本里学习“某书源系统性删字/换字/反义替换”的归一化档案，人工审核后全局下发，客户端确定性应用，避免逐章烧钱。

**Architecture:** 新增 `PolishAlignSample`（每源最多 20 章、TTL 30 天）与 `PolishSourceProfile`（按 `source_url` 唯一、`draft/verified/disabled` 三态）。学习用段级+字符级对齐做主证据、单源字频做辅助证据、LLM 只负责把候选归纳成最小规则集；留出集验证误改率 < 1% 后才允许 `verified` 下发。

**Tech Stack:** Django 6、DRF、difflib、requests、DeepSeek、正则。

**Spec:** `docs/superpowers/specs/2026-11-18-ai-content-polish-design.md` 第 11 节

## Global Constraints

- 档案按 `source_url` 全局共享；只有 `status=verified` 才下发给客户端。
- 不允许“无上下文盲插”缺字；缺字插入只能来自带例子的 `context_rules`。
- `char_map` 必须带例外白名单（如「乾坤」「乾隆」）。
- 反义对只在高置信对齐（≥90%、≥5 个不同章节）时生成；否则只进 `suspicious`。
- 样本每源最多 20 章、每章 4000 字、TTL 30 天；不记正文日志。
- 测试命令：`cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile -v 2`
- 迁移检查：`cd backend && .venv/Scripts/python.exe manage.py makemigrations --check --dry-run`

---

### Task 1: 档案与样本模型

**Files:**
- Modify: `backend/apps/polish/models.py`
- Test: `backend/apps/polish/tests/test_profile_models.py`

**Interfaces:**
- Consumes: `utils.models.BaseModel`
- Produces: `PolishSourceProfile`、`PolishAlignSample`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_profile_models.py
from django.test import TestCase
from apps.polish.models import PolishSourceProfile, PolishAlignSample


class ProfileModelTest(TestCase):
    def test_profile_unique_source(self):
        PolishSourceProfile.objects.create(source_url="https://src", profile_json={})
        with self.assertRaises(Exception):
            PolishSourceProfile.objects.create(source_url="https://src", profile_json={})

    def test_sample_unique_chapter(self):
        PolishAlignSample.objects.create(
            source_url="https://src", book_key="b|a", book_url="b", chapter_url="c",
            text="正文", content_hash="a" * 32,
        )
        with self.assertRaises(Exception):
            PolishAlignSample.objects.create(
                source_url="https://src", book_key="b|a", book_url="b", chapter_url="c",
                text="正文", content_hash="a" * 32,
            )
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_models -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 追加模型**

```python
class PolishSourceProfile(BaseModel):
    STATUS_CHOICES = (
        ("draft", "草稿"),
        ("verified", "已审核"),
        ("disabled", "已停用"),
    )

    source_url = models.CharField(max_length=700, unique=True)
    version = models.IntegerField(default=1)
    status = models.CharField(max_length=16, choices=STATUS_CHOICES, default="draft")
    profile_json = models.JSONField(default=dict)
    confidence = models.FloatField(default=0)
    sample_count = models.IntegerField(default=0)
    holdout_false_change_rate = models.FloatField(default=1.0)
    reviewer = models.CharField(max_length=64, blank=True, default="")
    review_note = models.CharField(max_length=512, blank=True, default="")

    class Meta:
        db_table = "cc_polish_source_profile"


class PolishAlignSample(BaseModel):
    source_url = models.CharField(max_length=700, db_index=True)
    book_key = models.CharField(max_length=128, db_index=True)
    book_url = models.CharField(max_length=700, db_index=True)
    chapter_url = models.CharField(max_length=1024)
    chapter_index = models.IntegerField(default=0)
    chapter_title = models.CharField(max_length=512, blank=True, default="")
    text = models.TextField()
    content_hash = models.CharField(max_length=32, db_index=True)
    expire_time = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "cc_polish_align_sample"
        unique_together = ("source_url", "chapter_url")
        indexes = [models.Index(fields=["book_key", "chapter_index"])]
```

- [ ] **Step 4: 生成迁移并测试**

Run:

```bash
cd backend && .venv/Scripts/python.exe manage.py makemigrations polish
cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_models -v 2
```

Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/models.py backend/apps/polish/migrations backend/apps/polish/tests/test_profile_models.py
git commit -m "feat(polish): add source profile and alignment sample models"
```

---

### Task 2: 样本采集与清理

**Files:**
- Create: `backend/apps/polish/profile_samples.py`
- Modify: `backend/apps/polish/services.py`（入队后调用）
- Create: `backend/apps/polish/management/commands/purge_align_samples.py`
- Test: `backend/apps/polish/tests/test_profile_samples.py`

**Interfaces:**
- Consumes: `PolishAlignSample`
- Produces:
  - `maybe_store_sample(source_url, book_key, book_url, chapter_url, chapter_index, title, text) -> bool`
  - `purge_expired_samples() -> int`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_profile_samples.py
from django.test import TestCase, override_settings
from django.utils import timezone
from apps.polish.models import PolishAlignSample
from apps.polish.profile_samples import maybe_store_sample, purge_expired_samples


@override_settings(POLISH_PROFILE_SAMPLE_LIMIT=4, POLISH_PROFILE_SAMPLE_CHARS=10,
                   POLISH_PROFILE_SAMPLE_TTL_DAYS=30)
class SampleTest(TestCase):
    def test_store_trims_and_limits(self):
        self.assertTrue(maybe_store_sample("s", "b|a", "b", "c1", 1, "第1章", "一" * 100))
        self.assertTrue(maybe_store_sample("s", "b|a", "b", "c2", 2, "第2章", "二" * 100))
        self.assertFalse(maybe_store_sample("s", "b|a", "b", "c3", 3, "第3章", "三" * 100))
        self.assertEqual(PolishAlignSample.objects.count(), 2)
        self.assertEqual(len(PolishAlignSample.objects.first().text), 10)

    @override_settings(POLISH_PROFILE_SAMPLE_LIMIT=2)
    def test_paired_sample_uses_reserved_quota(self):
        PolishAlignSample.objects.create(
            source_url="other", book_key="b|a", book_url="b2", chapter_url="c9",
            chapter_index=9, text="好文本", content_hash="b" * 32,
        )
        self.assertTrue(maybe_store_sample("s", "b|a", "b", "c1", 1, "第1章", "一" * 100))
        self.assertTrue(maybe_store_sample("s", "b|a", "b", "c9", 9, "第9章", "九" * 100))

    def test_dedupe_chapter(self):
        maybe_store_sample("s", "b|a", "b", "c1", 1, "第1章", "一" * 100)
        self.assertFalse(maybe_store_sample("s", "b|a", "b", "c1", 1, "第1章", "一" * 100))

    def test_purge_expired(self):
        sample = PolishAlignSample.objects.create(
            source_url="s", book_key="b|a", book_url="b", chapter_url="c", text="x",
            content_hash="a" * 32, chapter_index=1,
            expire_time=timezone.now() - timezone.timedelta(days=1),
        )
        self.assertEqual(purge_expired_samples(), 1)
        self.assertFalse(PolishAlignSample.objects.filter(id=sample.id).exists())
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_samples -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

```python
# backend/apps/polish/profile_samples.py
from datetime import timedelta

from django.conf import settings
from django.utils import timezone

from apps.polish.models import PolishAlignSample


def maybe_store_sample(
    source_url, book_key, book_url, chapter_url, chapter_index, title, text
) -> bool:
    if not source_url or not text:
        return False
    limit = int(getattr(settings, "POLISH_PROFILE_SAMPLE_LIMIT", 20))
    current = PolishAlignSample.objects.filter(source_url=source_url).count()
    if current >= limit:
        return False
    if PolishAlignSample.objects.filter(
        source_url=source_url, chapter_url=chapter_url
    ).exists():
        return False
    has_counterpart = PolishAlignSample.objects.filter(
        book_key=book_key, chapter_index=chapter_index
    ).exclude(source_url=source_url).exists()
    if not has_counterpart and current >= max(1, limit // 2):
        return False
    max_chars = int(getattr(settings, "POLISH_PROFILE_SAMPLE_CHARS", 4000))
    ttl_days = int(getattr(settings, "POLISH_PROFILE_SAMPLE_TTL_DAYS", 30))
    PolishAlignSample.objects.create(
        source_url=source_url,
        book_key=book_key,
        book_url=book_url,
        chapter_url=chapter_url,
        chapter_index=chapter_index,
        chapter_title=title or "",
        text=text[:max_chars],
        content_hash=__import__("hashlib").md5(text.encode("utf-8")).hexdigest(),
        expire_time=timezone.now() + timedelta(days=ttl_days),
    )
    return True


def purge_expired_samples() -> int:
    deleted, _ = PolishAlignSample.objects.filter(expire_time__lt=timezone.now()).delete()
    return deleted
```

在 `services.enqueue_chapters` 的 `accepted` 分支里、创建 job 之后调用：

```python
import hashlib
from django.conf import settings as django_settings
from apps.polish.profile_samples import maybe_store_sample
...
if getattr(django_settings, "POLISH_PROFILE_LEARNING_ENABLED", True):
    book_key = hashlib.md5(
        f"{book_name}|{book_author}".encode("utf-8")
    ).hexdigest()
    maybe_store_sample(
        source_url, book_key, book_url, ch["url"], ch["index"],
        ch.get("title", ""), text,
    )
```

在 `backend/config.py` 追加：

```python
POLISH_PROFILE_LEARNING_ENABLED = os.environ.get("LEGADO_POLISH_PROFILE_LEARNING", "1") == "1"
POLISH_PROFILE_SAMPLE_LIMIT = int(os.environ.get("LEGADO_POLISH_PROFILE_SAMPLE_LIMIT", "20"))
POLISH_PROFILE_SAMPLE_CHARS = int(os.environ.get("LEGADO_POLISH_PROFILE_SAMPLE_CHARS", "4000"))
POLISH_PROFILE_SAMPLE_TTL_DAYS = int(os.environ.get("LEGADO_POLISH_PROFILE_SAMPLE_TTL_DAYS", "30"))
```

- [ ] **Step 4: 清理命令**

```python
# backend/apps/polish/management/commands/purge_align_samples.py
from django.core.management.base import BaseCommand

from apps.polish.profile_samples import purge_expired_samples


class Command(BaseCommand):
    help = "清理过期的源特征对齐样本"

    def handle(self, *args, **options):
        n = purge_expired_samples()
        self.stdout.write(f"purged {n}")
```

- [ ] **Step 5: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_samples -v 2`
Expected: 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add backend/apps/polish/profile_samples.py backend/apps/polish/services.py backend/config.py backend/apps/polish/management/commands/purge_align_samples.py backend/apps/polish/tests/test_profile_samples.py
git commit -m "feat(polish): collect and purge multi-source alignment samples"
```

---

### Task 3: 多源对齐与候选聚合

**Files:**
- Create: `backend/apps/polish/align.py`
- Test: `backend/apps/polish/tests/test_align.py`

**Interfaces:**
- Consumes: 两段同章文本
- Produces:
  - `diff_ops(a: str, b: str) -> list[dict]`
  - `aggregate_candidates(pairs: list[tuple[str, str]]) -> dict`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_align.py
from django.test import SimpleTestCase
from apps.polish.align import diff_ops, aggregate_candidates, frequency_flags


class AlignTest(SimpleTestCase):
    def test_deleted_char(self):
        ops = diff_ops("他走进了屋子，坐了下来", "他走进了屋坐了下来")
        self.assertTrue(any("子" in op.get("a", "") or "，" in op.get("a", "") for op in ops))

    def test_variant_char(self):
        ops = diff_ops("他站在乾坤之间", "他站在干坤之间")
        self.assertTrue(any(op.get("a") == "乾" and op.get("b") == "干" for op in ops))

    def test_antonym(self):
        ops = diff_ops("他很高", "他很低")
        self.assertTrue(any(op.get("a") == "高" and op.get("b") == "低" for op in ops))

    def test_aggregate_counts(self):
        pairs = [("乾杯", "干杯"), ("乾坤", "干坤"), ("乾隆", "干隆")]
        result = aggregate_candidates(pairs)
        self.assertEqual(result["char_map"]["乾"]["to"], "干")
        self.assertGreaterEqual(result["char_map"]["乾"]["ratio"], 0.9)

    def test_frequency_flags(self):
        self.assertEqual(frequency_flags(["他跑得很快。" * 1000]), [])
        flags = frequency_flags(["他跑很快。" * 2000])
        self.assertTrue(any(f["char"] == "得" for f in flags))
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_align -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

```python
# backend/apps/polish/align.py
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
            flags.append({"char": ch, "observed": observed,
                          "expected": round(expected, 1)})
    return flags
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_align -v 2`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/align.py backend/apps/polish/tests/test_align.py
git commit -m "feat(polish): align multi-source chapters and aggregate candidates"
```

---

### Task 4: 档案应用器（确定性规则）

**Files:**
- Create: `backend/apps/polish/profile_apply.py`
- Test: `backend/apps/polish/tests/test_profile_apply.py`

**Interfaces:**
- Consumes: `profile_json`
- Produces: `apply_profile(text: str, profile: dict) -> tuple[str, list[dict]]`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_profile_apply.py
from django.test import SimpleTestCase
from apps.polish.profile_apply import apply_profile


PROFILE = {
    "char_map": {"乾": {"to": "干", "exceptions": ["乾坤", "乾隆"]}},
    "context_rules": [
        {"pattern": "([\\u4e00-\\u9fa5]{1,3})(很|非常|太)([\\u4e00-\\u9fa5])",
         "replacement": "$1得$2$3", "confidence": 0.9}
    ],
    "antonym_pairs": [{"from": "高", "to": "低", "direction": "a2b", "confidence": 0.95}],
}


class ApplyProfileTest(SimpleTestCase):
    def test_char_map_with_exception(self):
        text, changes = apply_profile("乾坤之间，乾杯", PROFILE)
        self.assertIn("乾坤", text)
        self.assertIn("干杯", text)
        self.assertGreaterEqual(len(changes), 1)

    def test_context_rule(self):
        text, _ = apply_profile("他跑很快", PROFILE)
        self.assertIn("跑得很快", text)

    def test_antonym(self):
        text, _ = apply_profile("他很高", PROFILE)
        self.assertEqual(text, "他很低")

    def test_empty_profile(self):
        text, changes = apply_profile("原文", {})
        self.assertEqual(text, "原文")
        self.assertEqual(changes, [])
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_apply -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

```python
# backend/apps/polish/profile_apply.py
import re


def _apply_char_map(text: str, src: str, dst: str, exceptions: list[str]) -> str:
    if not exceptions:
        return text.replace(src, dst)
    pattern = "(" + "|".join(map(re.escape, exceptions)) + ")"
    parts = re.split(pattern, text)
    return "".join(p if p in exceptions else p.replace(src, dst) for p in parts)


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
            changes.append({"rule": "char_map", "from": src, "to": dst,
                            "count": text.count(src)})
            text = new_text

    for rule in sorted(profile.get("context_rules") or [],
                       key=lambda r: -float(r.get("confidence", 0))):
        pattern = rule.get("pattern")
        replacement = rule.get("replacement")
        if not pattern or replacement is None:
            continue
        try:
            compiled = re.compile(pattern)
        except re.error:
            continue
        new_text, n = compiled.subn(replacement, text)
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
            changes.append({"rule": "antonym", "from": src, "to": dst,
                            "count": text.count(src)})
            text = text.replace(src, dst)

    if text == original:
        return text, []
    return text, changes
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_apply -v 2`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/profile_apply.py backend/apps/polish/tests/test_profile_apply.py
git commit -m "feat(polish): apply source profile rules deterministically"
```

---

### Task 5: 学习、留出集验证与审核命令

**Files:**
- Create: `backend/apps/polish/profile_learn.py`
- Modify: `backend/apps/polish/deepseek.py`
- Create: `backend/apps/polish/management/commands/learn_source_profile.py`
- Create: `backend/apps/polish/management/commands/approve_source_profile.py`
- Create: `backend/apps/polish/management/commands/disable_source_profile.py`
- Test: `backend/apps/polish/tests/test_profile_learn.py`

**Interfaces:**
- Consumes: `PolishAlignSample`、`aggregate_candidates`、`apply_profile`、DeepSeek
- Produces:
  - `build_draft(source_url) -> PolishSourceProfile`
  - `validate_profile(profile, pairs) -> tuple[float, float]`（false_change_rate, coverage_gain）
  - `approve(source_url, reviewer, note) -> PolishSourceProfile`

- [ ] **Step 1: 写失败测试（不调模型，只测留出集验证）**

```python
# backend/apps/polish/tests/test_profile_learn.py
from django.test import SimpleTestCase
from apps.polish.profile_learn import validate_profile


PROFILE = {"char_map": {"乾": {"to": "干", "exceptions": ["乾坤"]}}}


class ValidateProfileTest(SimpleTestCase):
    def test_false_change_rate(self):
        pairs = [("乾杯", "干杯"), ("乾坤", "乾坤"), ("乾杯", "干杯")]
        rate, gain = validate_profile(PROFILE, pairs)
        self.assertLess(rate, 0.5)
        self.assertGreater(gain, 0)

    def test_no_change(self):
        rate, gain = validate_profile({}, [("原文", "原文")])
        self.assertEqual(rate, 0.0)
        self.assertEqual(gain, 0.0)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_learn -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 在 `deepseek.py` 抽出 `complete_json`**

```python
def complete_json(system_prompt, user_content, model=None, max_tokens=2048):
    api_key = getattr(settings, "DEEPSEEK_API_KEY", "")
    if not api_key:
        raise RuntimeError("DEEPSEEK_API_KEY 未配置")
    base = getattr(settings, "DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
    model = resolve_model(model)
    body = {
        "model": model,
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
                time.sleep(2 ** attempt)
    raise last_error
```

并把 `clean_chapter` 改成复用它：

```python
def clean_chapter(title, text, model=None):
    user = json.dumps(
        {"title": title, "paragraphs": text.split("\n")},
        ensure_ascii=False,
    )
    return complete_json(SYSTEM_PROMPT, user, model, max_tokens=4096)
```

- [ ] **Step 4: 写 `profile_learn.py`**

```python
# backend/apps/polish/profile_learn.py
import json
import logging
import re

from apps.polish.align import aggregate_candidates, diff_ops, frequency_flags
from apps.polish.deepseek import _strip_fence
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
规则必须能对应到候选统计；不确定的不要写；反义对只在统计一致时写。"""


def _norm_title(title: str) -> str:
    return re.sub(r"\s|第[一二三四五六七八九十百千万零〇\d]+[章节卷回]", "", title or "")


def _pairs_for_source(source_url: str) -> list[tuple[str, str]]:
    targets = list(PolishAlignSample.objects.filter(source_url=source_url))
    pairs = []
    for s in targets:
        others = list(
            PolishAlignSample.objects.filter(
                book_key=s.book_key, chapter_index=s.chapter_index
            ).exclude(source_url=source_url)
        )
        if not others:
            title = _norm_title(s.chapter_title)
            if title:
                candidates = PolishAlignSample.objects.filter(
                    book_key=s.book_key
                ).exclude(source_url=source_url)
                others = [o for o in candidates if _norm_title(o.chapter_title) == title]
        for o in others:
            pairs.append((s.text, o.text))
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
        for c in changes:
            src = c.get("from")
            if src and not any(src in op.get("a", "") for op in ops):
                unsupported += c.get("count", 0)
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
    user = json.dumps({
        "candidates": candidates,
        "frequency_flags": frequency,
        "samples": [{"title": t, "text": x[:800]} for t, x in samples],
    }, ensure_ascii=False)

    from apps.polish.deepseek import complete_json
    payload, usage, _ = complete_json(PROFILE_SYSTEM, user, max_tokens=2048)
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

    # 只保留有统计/字频证据的规则，防止模型自由发挥
    freq_chars = {f["char"] for f in frequency}
    profile_json["char_map"] = {
        k: v for k, v in profile_json["char_map"].items()
        if (k in candidates["char_map"] and candidates["char_map"][k]["ratio"] >= 0.8)
        or k in freq_chars
    }
    profile_json["missing_chars"] = [
        c for c in profile_json["missing_chars"]
        if (c in candidates["missing_chars"]
            and candidates["missing_chars"][c]["ratio"] >= 0.8)
        or c in freq_chars
    ]
    profile_json["antonym_pairs"] = [
        p for p in profile_json["antonym_pairs"]
        if float(p.get("confidence", 0)) >= 0.9
        and candidates["char_map"].get(p.get("from"), {}).get("ratio", 0) >= 0.9
    ]
    rate, gain = validate_profile(profile_json, pairs)
    obj, _ = PolishSourceProfile.objects.update_or_create(
        source_url=source_url,
        defaults={
            "version": (PolishSourceProfile.objects.filter(
                source_url=source_url).values_list("version", flat=True).first() or 0) + 1,
            "status": "draft",
            "profile_json": profile_json,
            "confidence": profile_json["confidence"],
            "sample_count": PolishAlignSample.objects.filter(source_url=source_url).count(),
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
```

命令：

```python
# learn_source_profile.py
from django.core.management.base import BaseCommand
from apps.polish.profile_learn import build_draft

class Command(BaseCommand):
    def add_arguments(self, parser):
        parser.add_argument("--source-url", required=True)
    def handle(self, *args, **options):
        obj = build_draft(options["source_url"])
        self.stdout.write(f"draft v{obj.version} rate={obj.holdout_false_change_rate}")
```

```python
# approve_source_profile.py
from django.core.management.base import BaseCommand
from apps.polish.profile_learn import approve

class Command(BaseCommand):
    def add_arguments(self, parser):
        parser.add_argument("--source-url", required=True)
        parser.add_argument("--reviewer", required=True)
        parser.add_argument("--note", default="")
    def handle(self, *args, **options):
        obj = approve(options["source_url"], options["reviewer"], options["note"])
        self.stdout.write(f"verified v{obj.version}")
```

```python
# disable_source_profile.py
from django.core.management.base import BaseCommand
from apps.polish.models import PolishSourceProfile

class Command(BaseCommand):
    def add_arguments(self, parser):
        parser.add_argument("--source-url", required=True)
    def handle(self, *args, **options):
        n = PolishSourceProfile.objects.filter(
            source_url=options["source_url"]
        ).update(status="disabled")
        self.stdout.write(f"disabled {n}")
```

- [ ] **Step 5: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_learn -v 2`
Expected: 2 tests PASS

- [ ] **Step 6: Commit**

```bash
git add backend/apps/polish/profile_learn.py backend/apps/polish/deepseek.py backend/apps/polish/management/commands backend/apps/polish/tests/test_profile_learn.py
git commit -m "feat(polish): learn, validate and approve source profiles"
```

---

### Task 6: 档案下发 API

**Files:**
- Modify: `backend/apps/polish/views_app.py`
- Modify: `backend/apps/legado/urls.py`
- Test: `backend/apps/polish/tests/test_profile_api.py`

**Interfaces:**
- Produces: `GET /api/legado/app/polish/source-profile?sourceUrl=`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_profile_api.py
from django.test import TestCase
from rest_framework.test import APIClient
from apps.polish.models import PolishSourceProfile


class ProfileApiTest(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.headers = {"HTTP_X_USER_PHONE": "13800000006"}

    def test_verified_profile(self):
        PolishSourceProfile.objects.create(
            source_url="https://src", status="verified", version=3,
            profile_json={"char_map": {"乾": {"to": "干", "exceptions": ["乾坤"]}}},
            confidence=0.95,
        )
        resp = self.client.get(
            "/api/legado/app/polish/source-profile?sourceUrl=https%3A%2F%2Fsrc",
            **self.headers,
        )
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["version"], 3)
        self.assertEqual(resp.json()["profile"]["char_map"]["乾"]["to"], "干")

    def test_draft_not_served(self):
        PolishSourceProfile.objects.create(
            source_url="https://src", status="draft", profile_json={}
        )
        resp = self.client.get(
            "/api/legado/app/polish/source-profile?sourceUrl=https%3A%2F%2Fsrc",
            **self.headers,
        )
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json(), {})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_api -v 2`
Expected: FAIL / 404

- [ ] **Step 3: 写视图**

```python
class PolishSourceProfileView(NoAuthAPIView):
    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        source_url = request.query_params.get("sourceUrl") or ""
        obj = PolishSourceProfile.objects.filter(
            source_url=source_url, status="verified"
        ).first()
        if not obj:
            return Response({})
        return Response({
            "sourceUrl": obj.source_url,
            "version": obj.version,
            "confidence": obj.confidence,
            "profile": obj.profile_json,
            "updatedAt": int(obj.update_time.timestamp() * 1000) if obj.update_time else 0,
        })
```

在 `backend/apps/legado/urls.py` 追加：

```python
    path("app/polish/source-profile", PolishSourceProfileView.as_view(),
         name="legado-app-polish-source-profile"),
```

并在导入中加入 `PolishSourceProfileView`。

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_api -v 2`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/views_app.py backend/apps/legado/urls.py backend/apps/polish/tests/test_profile_api.py
git commit -m "feat(polish): serve verified source profiles to app"
```

---

### Task 7: 合成源端到端验证

**Files:**
- Create: `backend/apps/polish/tests/test_profile_e2e.py`

**Interfaces:**
- Consumes: 上述全部
- Produces: 一条可复跑的合成用例

- [ ] **Step 1: 写测试**

```python
# backend/apps/polish/tests/test_profile_e2e.py
from django.test import TestCase, override_settings
from apps.polish.align import aggregate_candidates
from apps.polish.profile_apply import apply_profile


@override_settings()
class ProfileE2ETest(TestCase):
    def test_learn_dedede_and_qian(self):
        good = [
            "他跑得很快。",
            "她说得很好。",
            "他站在乾坤之间。",
            "他喝了一杯干啤。",
        ]
        bad = [
            "他跑很快。",
            "她说很好。",
            "他站在干坤之间。",
            "他喝了一杯干啤。",
        ]
        candidates = aggregate_candidates(list(zip(good, bad)))
        self.assertIn("得", candidates["missing_chars"])
        self.assertEqual(candidates["char_map"]["乾"]["to"], "干")

        profile = {
            "char_map": {"乾": {"to": "干", "exceptions": ["乾坤"]}},
            "context_rules": [
                {"pattern": "([跑说唱跳])(很|非常|太)([\\u4e00-\\u9fa5])",
                 "replacement": "$1得$2$3", "confidence": 0.9}
            ],
            "antonym_pairs": [],
        }
        out, _ = apply_profile("他跑很快。她说很好。他站在干坤之间。", profile)
        self.assertEqual(out, "他跑得很快。她说得很好。他站在乾坤之间。")
```

- [ ] **Step 2: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_profile_e2e -v 2`
Expected: 1 test PASS

- [ ] **Step 3: Commit**

```bash
git add backend/apps/polish/tests/test_profile_e2e.py
git commit -m "test(polish): synthetic source profile end-to-end"
```

---

### Task 8: 生产启用

**Files:** 无代码改动

- [ ] **Step 1: 采样开关与 TTL**

确认生产环境变量：

```bash
LEGADO_POLISH_PROFILE_LEARNING=1
LEGADO_POLISH_PROFILE_SAMPLE_LIMIT=20
LEGADO_POLISH_PROFILE_SAMPLE_CHARS=4000
LEGADO_POLISH_PROFILE_SAMPLE_TTL_DAYS=30
```

- [ ] **Step 2: 每日清理**

在后台“定时任务”新增：调用目标 `apps.polish.profile_samples.purge_expired_samples`，cron `0 30 4 * * *`。

- [ ] **Step 3: 学习与审核**

样本积累到 10 章以上后：

```bash
cd /opt/legado/backend && .venv/bin/python manage.py learn_source_profile --source-url=<书源URL>
cd /opt/legado/backend && .venv/bin/python manage.py approve_source_profile --source-url=<书源URL> --reviewer=<你的名字>
```

- [ ] **Step 4: 验证**

```bash
curl -s "http://127.0.0.1:9666/api/legado/app/polish/source-profile?sourceUrl=<URL编码后的书源>" \
  -H "X-User-Phone: <手机号>"
```

Expected: 返回 `version/profile`，客户端可拉到。
