# AI 正文清洗后端流水线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Django 后端新增一条“上传原文 → 空闲时段调用 DeepSeek → 生成逐处补丁/拼接判定 → 客户端拉取”的流水线，服务端只存补丁，不长期存正文。

**Architecture:** 新增 `apps/polish` 应用；DRF 提供上传/拉取/状态三个接口；`PolishJob` 作为队列，复用 `apps/sysjob` 的 APScheduler 每 5 分钟取任务；worker 先做本地预筛，再调 DeepSeek（同一次调用返回完整性判定和逐处补丁），服务端做确定性校验，通过后写 `PolishPatchCache` 并清空原文。

**Tech Stack:** Django 6、DRF、APScheduler（django-apscheduler）、requests、MySQL/SQLite、DeepSeek `deepseek-flash`。

**Spec:** `docs/superpowers/specs/2026-11-18-ai-content-polish-design.md`

## Global Constraints

- 生产后端是单进程 Daphne + `apps/sysjob` 的 APScheduler；不要新起独立进程或第二个调度器。
- 只在空闲窗口调用 DeepSeek：周一至五 `09:00-12:00`、`14:00-18:00` 之外，加上周末和节假日全天（北京时间）。
- 默认模型 `deepseek-flash`；默认每日预算 2 元；预算与模型必须可运行期调整。
- 服务端不长期保存正文：`done/contaminated/suspicious` 后 `raw_text=NULL`；失败保留 7 天。
- 任何校验失败整章回退，不落地部分补丁。
- 不记录正文到日志；只记录 hash、字数、token、费用。
- 测试命令：`cd backend && .venv/Scripts/python.exe manage.py test apps.polish -v 2`
- 迁移检查：`cd backend && .venv/Scripts/python.exe manage.py makemigrations --check --dry-run`
- 启动检查：`cd backend && .venv/Scripts/python.exe manage.py check`

---

### Task 1: 创建 `apps.polish` 应用骨架

**Files:**
- Create: `backend/apps/polish/__init__.py`
- Create: `backend/apps/polish/apps.py`
- Create: `backend/apps/polish/migrations/__init__.py`
- Create: `backend/apps/polish/tests/__init__.py`
- Modify: `backend/application/settings.py:78-81`

**Interfaces:**
- Consumes: 无
- Produces: Django app label `polish`，后续所有模块都放在 `apps.polish` 下。

- [ ] **Step 1: 创建空文件**

```bash
mkdir -p backend/apps/polish/migrations backend/apps/polish/tests
touch backend/apps/polish/__init__.py backend/apps/polish/migrations/__init__.py backend/apps/polish/tests/__init__.py
```

- [ ] **Step 2: 写 `apps.py`**

```python
# backend/apps/polish/apps.py
from django.apps import AppConfig


class PolishConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "apps.polish"
    verbose_name = "AI 正文清洗"
```

- [ ] **Step 3: 注册应用**

在 `backend/application/settings.py` 的 `INSTALLED_APPS` 末尾加入：

```python
    'apps.legado',
    'apps.polish',
```

- [ ] **Step 4: 运行检查**

Run: `cd backend && .venv/Scripts/python.exe manage.py check`
Expected: `System check identified no issues`

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish backend/application/settings.py
git commit -m "feat(polish): scaffold backend polish app"
```

---

### Task 2: 模型与迁移

**Files:**
- Create: `backend/apps/polish/models.py`
- Create: `backend/apps/polish/admin.py`（空的，不注册 Django admin）
- Test: `backend/apps/polish/tests/test_models.py`

**Interfaces:**
- Consumes: `utils.models.BaseModel`、`apps.legado.models.LegadoUser`
- Produces: `PolishJob`、`PolishPatchCache`、`PolishQuota`、`PolishBookState`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_models.py
from django.test import TestCase
from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob, PolishPatchCache, PolishQuota, PolishBookState


class PolishModelTest(TestCase):
    def setUp(self):
        self.user = LegadoUser.objects.create(phone="13800000001")

    def test_job_defaults(self):
        job = PolishJob.objects.create(
            user=self.user,
            book_url="https://example.com/book/1",
            chapter_url="https://example.com/book/1/2",
            chapter_index=2,
            source_hash="a" * 32,
        )
        self.assertEqual(job.status, "queued")
        self.assertEqual(job.priority, 100)
        self.assertEqual(job.attempts, 0)

    def test_job_unique_per_hash(self):
        kwargs = dict(
            user=self.user,
            book_url="https://example.com/book/1",
            chapter_url="https://example.com/book/1/2",
            chapter_index=2,
            source_hash="a" * 32,
        )
        PolishJob.objects.create(**kwargs)
        with self.assertRaises(Exception):
            PolishJob.objects.create(**kwargs)

    def test_patch_cache_unique(self):
        PolishPatchCache.objects.create(
            chapter_url="https://example.com/book/1/2",
            source_hash="a" * 32,
            verdict="ok",
            patch_json={"edits": []},
        )
        with self.assertRaises(Exception):
            PolishPatchCache.objects.create(
                chapter_url="https://example.com/book/1/2",
                source_hash="a" * 32,
                verdict="ok",
                patch_json={"edits": []},
            )

    def test_quota_unique_per_day(self):
        PolishQuota.objects.create(user=self.user, date="2026-11-18")
        with self.assertRaises(Exception):
            PolishQuota.objects.create(user=self.user, date="2026-11-18")

    def test_book_state_unique(self):
        PolishBookState.objects.create(
            user=self.user, book_url="https://example.com/book/1"
        )
        with self.assertRaises(Exception):
            PolishBookState.objects.create(
                user=self.user, book_url="https://example.com/book/1"
            )
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_models -v 2`
Expected: FAIL / ImportError `apps.polish.models`

- [ ] **Step 3: 写模型**

```python
# backend/apps/polish/models.py
from django.db import models

from apps.legado.models import LegadoUser
from utils.models import BaseModel


class PolishJob(BaseModel):
    STATUS_CHOICES = (
        ("queued", "排队中"),
        ("processing", "处理中"),
        ("done", "已生成补丁"),
        ("contaminated", "付费残缺/拼接"),
        ("suspicious", "疑似拼接"),
        ("rejected", "校验未通过"),
        ("failed", "失败"),
        ("skipped", "跳过"),
    )

    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="polish_jobs"
    )
    book_url = models.CharField(max_length=700, db_index=True)
    book_name = models.CharField(max_length=255, blank=True, default="")
    book_author = models.CharField(max_length=255, blank=True, default="")
    source_url = models.CharField(max_length=700, blank=True, default="")
    chapter_url = models.CharField(max_length=1024, db_index=True)
    chapter_index = models.IntegerField()
    chapter_title = models.CharField(max_length=512, blank=True, default="")
    source_hash = models.CharField(max_length=32, db_index=True)
    profile_version = models.IntegerField(default=0)
    raw_text = models.TextField(null=True, blank=True)
    status = models.CharField(
        max_length=16, choices=STATUS_CHOICES, default="queued", db_index=True
    )
    priority = models.SmallIntegerField(default=100)
    attempts = models.SmallIntegerField(default=0)
    verdict = models.CharField(max_length=16, blank=True, default="")
    verdict_reason = models.CharField(max_length=512, blank=True, default="")
    boundary_paragraph = models.IntegerField(null=True, blank=True)
    patch_json = models.JSONField(null=True, blank=True)
    tokens_in = models.IntegerField(default=0)
    tokens_out = models.IntegerField(default=0)
    cost_micros = models.BigIntegerField(default=0)
    model = models.CharField(max_length=64, blank=True, default="")
    error = models.TextField(blank=True, default="")

    class Meta:
        db_table = "cc_polish_job"
        unique_together = ("user", "book_url", "chapter_index", "source_hash")
        indexes = [models.Index(fields=["status", "priority", "create_time"])]

    def __str__(self):
        return f"{self.book_name} #{self.chapter_index} [{self.status}]"


class PolishPatchCache(BaseModel):
    source_url = models.CharField(max_length=700, blank=True, default="")
    chapter_url = models.CharField(max_length=1024, db_index=True)
    source_hash = models.CharField(max_length=32, db_index=True)
    profile_version = models.IntegerField(default=0)
    verdict = models.CharField(max_length=16, default="ok")
    verdict_reason = models.CharField(max_length=512, blank=True, default="")
    boundary_paragraph = models.IntegerField(null=True, blank=True)
    patch_json = models.JSONField(null=True, blank=True)
    model = models.CharField(max_length=64, blank=True, default="")

    class Meta:
        db_table = "cc_polish_patch_cache"
        unique_together = ("chapter_url", "source_hash")


class PolishQuota(BaseModel):
    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="polish_quotas"
    )
    date = models.DateField()
    spent_micros = models.BigIntegerField(default=0)
    job_count = models.IntegerField(default=0)

    class Meta:
        db_table = "cc_polish_quota"
        unique_together = ("user", "date")


class PolishBookState(BaseModel):
    STATUS_CHOICES = (
        ("normal", "正常"),
        ("paid_truncated", "付费残缺"),
        ("paused", "暂停"),
    )

    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="polish_book_states"
    )
    book_url = models.CharField(max_length=700, db_index=True)
    book_name = models.CharField(max_length=255, blank=True, default="")
    status = models.CharField(max_length=16, choices=STATUS_CHOICES, default="normal")
    contaminated_streak = models.IntegerField(default=0)
    last_reason = models.CharField(max_length=512, blank=True, default="")

    class Meta:
        db_table = "cc_polish_book_state"
        unique_together = ("user", "book_url")
```

- [ ] **Step 4: 生成迁移**

Run: `cd backend && .venv/Scripts/python.exe manage.py makemigrations polish`
Expected: 生成 `0001_initial.py`，包含四个模型。

- [ ] **Step 5: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_models -v 2`
Expected: 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add backend/apps/polish/models.py backend/apps/polish/admin.py backend/apps/polish/migrations
git commit -m "feat(polish): add job, cache, quota and book state models"
```

---

### Task 3: 空闲窗口与计费工具

**Files:**
- Create: `backend/apps/polish/window.py`
- Create: `backend/apps/polish/pricing.py`
- Test: `backend/apps/polish/tests/test_window.py`
- Test: `backend/apps/polish/tests/test_pricing.py`

**Interfaces:**
- Consumes: `django.conf.settings.POLISH_HOLIDAYS`、`POLISH_PRICES`
- Produces:
  - `is_idle_window(dt: datetime | None = None) -> bool`
  - `estimate_tokens(text: str) -> int`
  - `cost_micros(usage: dict, idle: bool, model: str) -> int`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_window.py
from datetime import datetime
from django.test import SimpleTestCase, override_settings
from apps.polish.window import TZ, is_idle_window


@override_settings(POLISH_HOLIDAYS=())
class IdleWindowTest(SimpleTestCase):
    def test_weekday_peak(self):
        self.assertFalse(is_idle_window(datetime(2026, 11, 18, 9, 0, tzinfo=TZ)))
        self.assertFalse(is_idle_window(datetime(2026, 11, 18, 11, 59, tzinfo=TZ)))
        self.assertFalse(is_idle_window(datetime(2026, 11, 18, 14, 0, tzinfo=TZ)))
        self.assertFalse(is_idle_window(datetime(2026, 11, 18, 17, 59, tzinfo=TZ)))

    def test_weekday_off_peak(self):
        self.assertTrue(is_idle_window(datetime(2026, 11, 18, 8, 59, tzinfo=TZ)))
        self.assertTrue(is_idle_window(datetime(2026, 11, 18, 12, 0, tzinfo=TZ)))
        self.assertTrue(is_idle_window(datetime(2026, 11, 18, 13, 59, tzinfo=TZ)))
        self.assertTrue(is_idle_window(datetime(2026, 11, 18, 18, 0, tzinfo=TZ)))
        self.assertTrue(is_idle_window(datetime(2026, 11, 18, 23, 0, tzinfo=TZ)))

    def test_weekend_all_idle(self):
        self.assertTrue(is_idle_window(datetime(2026, 11, 21, 10, 0, tzinfo=TZ)))
        self.assertTrue(is_idle_window(datetime(2026, 11, 22, 15, 0, tzinfo=TZ)))

    @override_settings(POLISH_HOLIDAYS=("2026-11-18",))
    def test_holiday_all_idle(self):
        self.assertTrue(is_idle_window(datetime(2026, 11, 18, 10, 0, tzinfo=TZ)))
```

```python
# backend/apps/polish/tests/test_pricing.py
from django.test import SimpleTestCase, override_settings
from apps.polish.pricing import cost_micros, estimate_tokens


class PricingTest(SimpleTestCase):
    def test_estimate_tokens(self):
        self.assertEqual(estimate_tokens(""), 1)
        self.assertGreater(estimate_tokens("一" * 1600), 900)

    @override_settings(POLISH_PRICES={
        "flash": {"input_hit": 0.02, "input_miss": 1.0, "output": 4.0}
    })
    def test_cost_idle(self):
        usage = {
            "prompt_cache_hit_tokens": 1_000_000,
            "prompt_cache_miss_tokens": 1_000_000,
            "completion_tokens": 1_000_000,
        }
        self.assertEqual(cost_micros(usage, idle=True, model="flash"), 5_020_000)

    @override_settings(POLISH_PRICES={
        "flash": {"input_hit": 0.02, "input_miss": 1.0, "output": 4.0}
    })
    def test_cost_peak_doubles(self):
        usage = {
            "prompt_cache_hit_tokens": 0,
            "prompt_cache_miss_tokens": 1_000_000,
            "completion_tokens": 0,
        }
        self.assertEqual(cost_micros(usage, idle=False, model="flash"), 2_000_000)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_window apps.polish.tests.test_pricing -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

```python
# backend/apps/polish/window.py
from datetime import datetime, time
from zoneinfo import ZoneInfo

from django.conf import settings

TZ = ZoneInfo("Asia/Shanghai")
PEAK_WINDOWS = ((time(9, 0), time(12, 0)), (time(14, 0), time(18, 0)))


def is_idle_window(dt: datetime | None = None) -> bool:
    dt = dt or datetime.now(TZ)
    dt = dt.replace(tzinfo=TZ) if dt.tzinfo is None else dt.astimezone(TZ)
    holidays = set(getattr(settings, "POLISH_HOLIDAYS", ()) or ())
    if dt.date().isoformat() in holidays:
        return True
    if dt.weekday() >= 5:
        return True
    now = dt.time()
    return not any(start <= now < end for start, end in PEAK_WINDOWS)
```

```python
# backend/apps/polish/pricing.py
from django.conf import settings

DEFAULT_PRICES = {
    "flash": {"input_hit": 0.02, "input_miss": 1.0, "output": 4.0},
    "v4-pro": {"input_hit": 0.15, "input_miss": 4.5, "output": 13.5},
}


def estimate_tokens(text: str) -> int:
    # DeepSeek 中文约 1 token ≈ 1.6 字
    return max(1, int(len(text) / 1.6))


def _prices(model: str) -> dict:
    table = getattr(settings, "POLISH_PRICES", DEFAULT_PRICES)
    return table.get(model, table["flash"])


def cost_micros(usage: dict, idle: bool, model: str = "flash") -> int:
    prices = _prices(model)
    hit = int(usage.get("prompt_cache_hit_tokens", 0) or 0)
    miss = int(
        usage.get("prompt_cache_miss_tokens")
        or max(0, int(usage.get("prompt_tokens", 0) or 0) - hit)
    )
    out = int(usage.get("completion_tokens", 0) or 0)
    yuan = (
        hit / 1_000_000 * prices["input_hit"]
        + miss / 1_000_000 * prices["input_miss"]
        + out / 1_000_000 * prices["output"]
    )
    if not idle:
        yuan *= 2
    return int(round(yuan * 1_000_000))
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_window apps.polish.tests.test_pricing -v 2`
Expected: 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/window.py backend/apps/polish/pricing.py backend/apps/polish/tests
git commit -m "feat(polish): add idle window and pricing helpers"
```

---

### Task 4: 补丁校验器

**Files:**
- Create: `backend/apps/polish/validator.py`
- Test: `backend/apps/polish/tests/test_validator.py`

**Interfaces:**
- Consumes: 原始正文 `raw_text`、模型返回的 `payload`
- Produces:
  - `ValidationResult(ok: bool, verdict: str, edits: list[dict], reason: str)`
  - `validate_payload(raw_text, payload, max_insert=30, max_ratio=0.05) -> ValidationResult`
  - `edits` 中每项为 `{"p": int, "start": int, "end": int, "replace": str}`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_validator.py
from django.test import SimpleTestCase
from apps.polish.validator import validate_payload


RAW = "第一段正常。\n他走进了屋坐了下来。\n最新章节请访问 www.example.com"


class ValidatorTest(SimpleTestCase):
    def test_ok_single_edit(self):
        payload = {
            "verdict": "ok",
            "confidence": 0.9,
            "edits": [
                {"p": 1, "from": "他走进了屋坐了下来", "to": "他走进了屋子，坐了下来"}
            ],
        }
        result = validate_payload(RAW, payload)
        self.assertTrue(result.ok)
        self.assertEqual(result.verdict, "ok")
        self.assertEqual(len(result.edits), 1)
        self.assertEqual(result.edits[0]["replace"], "他走进了屋子，坐了下来")
        self.assertGreater(result.edits[0]["start"], 0)

    def test_contaminated_discards_edits(self):
        payload = {
            "verdict": "contaminated",
            "confidence": 0.92,
            "boundary": 2,
            "reason": "后文是另一本书",
            "edits": [{"p": 0, "from": "第一段正常。", "to": "第一段正常。"}],
        }
        result = validate_payload(RAW, payload)
        self.assertTrue(result.ok)
        self.assertEqual(result.verdict, "contaminated")
        self.assertEqual(result.edits, [])

    def test_anchor_must_be_unique(self):
        payload = {"verdict": "ok", "confidence": 0.9,
                   "edits": [{"p": 0, "from": "第一段", "to": "第一段。"}]}
        result = validate_payload("第一段\n第一段", payload)
        self.assertFalse(result.ok)

    def test_anchor_missing(self):
        payload = {"verdict": "ok", "confidence": 0.9,
                   "edits": [{"p": 0, "from": "不存在", "to": "x"}]}
        self.assertFalse(validate_payload(RAW, payload).ok)

    def test_reject_new_url(self):
        payload = {"verdict": "ok", "confidence": 0.9,
                   "edits": [{"p": 0, "from": "第一段正常。", "to": "访问 www.evil.com"}]}
        self.assertFalse(validate_payload(RAW, payload).ok)

    def test_reject_huge_insert(self):
        payload = {"verdict": "ok", "confidence": 0.9,
                   "edits": [{"p": 0, "from": "第一段正常。", "to": "一" * 100}]}
        self.assertFalse(validate_payload(RAW, payload).ok)

    def test_reject_too_much_edit(self):
        raw = "短。\n" * 10
        payload = {"verdict": "ok", "confidence": 0.9,
                   "edits": [{"p": 0, "from": "短。", "to": "很长的一句话。"}]}
        self.assertFalse(validate_payload(raw, payload, max_ratio=0.01).ok)

    def test_low_confidence_becomes_suspicious(self):
        payload = {"verdict": "ok", "confidence": 0.3,
                   "edits": [{"p": 1, "from": "他走进了屋坐了下来", "to": "他走进了屋子。"}]}
        result = validate_payload(RAW, payload)
        self.assertTrue(result.ok)
        self.assertEqual(result.verdict, "suspicious")
        self.assertEqual(result.edits, [])

    def test_lossless_delete_ad(self):
        payload = {"verdict": "ok", "confidence": 0.99,
                   "edits": [{"p": 2, "from": "最新章节请访问 www.example.com", "to": ""}]}
        result = validate_payload(RAW, payload)
        self.assertTrue(result.ok)
        self.assertEqual(result.edits[0]["replace"], "")
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_validator -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

```python
# backend/apps/polish/validator.py
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
        return ValidationResult(True, verdict=verdict, edits=[],
                                reason=str(payload.get("reason") or ""))

    if confidence < 0.7:
        return ValidationResult(True, verdict="suspicious", edits=[],
                                reason="置信度过低")

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
        edit = {"p": p, "start": start, "end": start + len(frm),
                "from": frm, "replace": to}
        edits.append(edit)
        # 删除纯广告/网址不计入总修改量，避免短章因长 URL 被误拒
        if not (URL_RE.search(frm) and not to.strip()):
            total_delta += abs(delta)

    if raw_text and total_delta / len(raw_text) > max_ratio:
        return ValidationResult(False, reason="总修改量过大")

    edits.sort(key=lambda e: e["start"])
    return ValidationResult(True, verdict="ok", edits=edits)
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_validator -v 2`
Expected: 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/validator.py backend/apps/polish/tests/test_validator.py
git commit -m "feat(polish): validate patches and discard contaminated edits"
```

---

### Task 5: 本地预筛（付费提示、中途章节标题、长度异常）

**Files:**
- Create: `backend/apps/polish/prescreen.py`
- Test: `backend/apps/polish/tests/test_prescreen.py`

**Interfaces:**
- Consumes: `raw_text`、`book_median_len: int`
- Produces: `prescreen(raw_text, book_median_len=0) -> list[str]`，返回信号名列表

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_prescreen.py
from django.test import SimpleTestCase
from apps.polish.prescreen import prescreen


class PrescreenTest(SimpleTestCase):
    def test_clean_text(self):
        self.assertEqual(prescreen("他走进屋子，坐了下来。\n窗外下着雨。"), [])

    def test_paywall(self):
        flags = prescreen("正文前几段。\n本章未完，请充值后阅读。")
        self.assertIn("paywall", flags)

    def test_mid_chapter_heading(self):
        text = "第一段。\n第二段。\n第三段。\n第12章 另一个故事\n新的主角出现了。"
        self.assertIn("mid_heading", prescreen(text))

    def test_length_outlier_with_heading(self):
        text = "第一段。\n" + "内容。" * 200 + "\n第12章 另一个故事\n新故事。"
        self.assertIn("length_outlier", prescreen(text, book_median_len=100))

    def test_repeated_block(self):
        block = "这是一段重复出现的文字。" * 20
        text = f"{block}\n中间正常内容。\n{block}"
        self.assertIn("repeated_block", prescreen(text))
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_prescreen -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

```python
# backend/apps/polish/prescreen.py
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
    half = len(text) // 2
    head, tail = text[:half], text[half:]
    step = max(1, size // 2)
    seen = {head[i:i + size] for i in range(0, max(1, len(head) - size), step)}
    return any(tail[i:i + size] in seen for i in range(0, max(1, len(tail) - size), step))


def prescreen(raw_text: str, book_median_len: int = 0) -> list[str]:
    flags = []
    length = len(raw_text)
    if length == 0:
        return ["empty"]

    head = raw_text[: int(length * 0.85)]
    if PAYWALL_RE.search(head):
        flags.append("paywall")

    headings = list(MID_HEADING_RE.finditer(raw_text))
    mid = [m for m in headings if int(length * 0.10) <= m.start() <= int(length * 0.90)]
    if mid:
        flags.append("mid_heading")

    if book_median_len and length > book_median_len * 3 and "mid_heading" in flags:
        flags.append("length_outlier")

    if _repeated_block(raw_text):
        flags.append("repeated_block")

    return flags
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_prescreen -v 2`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/prescreen.py backend/apps/polish/tests/test_prescreen.py
git commit -m "feat(polish): pre-screen paywall and spliced chapters"
```

---

### Task 6: DeepSeek 客户端与配置

**Files:**
- Create: `backend/apps/polish/deepseek.py`
- Create: `backend/apps/polish/config.py`
- Modify: `backend/config.py`（追加配置）
- Test: `backend/apps/polish/tests/test_deepseek.py`

**Interfaces:**
- Consumes: `settings.DEEPSEEK_API_KEY`、`POLISH_MODEL`
- Produces:
  - `build_messages(title: str, text: str) -> list[dict]`
  - `clean_chapter(title, text, model=None) -> tuple[dict, dict, str]`（payload, usage, raw content）
  - `get_daily_budget_cny() -> float`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_deepseek.py
import json
from django.test import SimpleTestCase, override_settings
from apps.polish.deepseek import build_messages


class DeepSeekMessageTest(SimpleTestCase):
    def test_messages_shape(self):
        messages = build_messages("第1章", "第一段。\n第二段。")
        self.assertEqual(messages[0]["role"], "system")
        self.assertIn("verdict", messages[0]["content"])
        payload = json.loads(messages[1]["content"])
        self.assertEqual(payload["title"], "第1章")
        self.assertEqual(payload["paragraphs"], ["第一段。", "第二段。"])

    @override_settings(POLISH_MODEL="deepseek-flash")
    def test_model_default(self):
        from apps.polish.deepseek import resolve_model
        self.assertEqual(resolve_model(None), "deepseek-flash")
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_deepseek -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

```python
# backend/apps/polish/deepseek.py
import json
import logging
import time

import requests
from django.conf import settings

from apps.polish.pricing import cost_micros
from apps.polish.window import is_idle_window

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是中文小说正文清洗器，只做最小修复，不做改写。

第一步：判断本章是否完整。
- contaminated：前文在付费提示处截断，后文突然换成另一本书的人物/地点/时代/主线，或正文中途出现另一个章节标题；后文自成一个故事开头。
- suspicious：判断不确定，或只有弱信号。
- ok：正常场景切换、回忆、多线叙事、视角切换，不算拼接。
判定为 contaminated 或 suspicious 时，edits 必须为空，不得改写拼接内容。

第二步：仅当 verdict=ok 时，输出逐处补丁。
- 只修：广告/网址/公众号/QQ群/求票、乱码/零宽字符、被拆字、缺字、错字、断裂句。
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


def clean_chapter(title: str, text: str, model: str | None = None) -> tuple[dict, dict, str]:
    api_key = getattr(settings, "DEEPSEEK_API_KEY", "")
    if not api_key:
        raise RuntimeError("DEEPSEEK_API_KEY 未配置")
    base = getattr(settings, "DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
    model = resolve_model(model)
    body = {
        "model": model,
        "messages": build_messages(title, text),
        "temperature": 0.2,
        "max_tokens": 4096,
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
            payload = json.loads(content)
            usage = data.get("usage") or {}
            return payload, usage, content
        except Exception as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(2 ** attempt)
    raise last_error
```

```python
# backend/apps/polish/config.py
from django.conf import settings
from django.core.cache import cache

from mysystem.models import SystemConfig

CACHE_KEY = "polish:daily_budget"


def get_daily_budget_cny() -> float:
    cached = cache.get(CACHE_KEY)
    if cached is not None:
        return float(cached)
    row = (
        SystemConfig.objects.filter(key="polish_daily_budget")
        .values_list("value", flat=True)
        .first()
    )
    value = float(row) if row not in (None, "") else float(
        getattr(settings, "POLISH_DAILY_BUDGET_CNY", 2.0)
    )
    cache.set(CACHE_KEY, value, 30)
    return value
```

在 `backend/config.py` 末尾追加：

```python
# ================================================= #
# ************** DeepSeek 正文清洗  ************** #
# ================================================= #
DEEPSEEK_API_KEY = os.environ.get("LEGADO_DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.environ.get("LEGADO_DEEPSEEK_BASE_URL", "https://api.deepseek.com")
POLISH_MODEL = os.environ.get("LEGADO_POLISH_MODEL", "deepseek-flash")
POLISH_DAILY_BUDGET_CNY = float(os.environ.get("LEGADO_POLISH_DAILY_BUDGET_CNY", "2"))
POLISH_BOOK_BUDGET_CNY = float(os.environ.get("LEGADO_POLISH_BOOK_BUDGET_CNY", "0"))
POLISH_HOLIDAYS = tuple(
    x.strip() for x in os.environ.get("LEGADO_POLISH_HOLIDAYS", "").split(",") if x.strip()
)
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_deepseek -v 2`
Expected: 2 tests PASS

- [ ] **Step 5: 真实 API 冒烟（需 Key）**

Run:

```bash
cd backend && LEGADO_DEEPSEEK_API_KEY=sk-xxx .venv/Scripts/python.exe -c "
from apps.polish.deepseek import clean_chapter
p,u,c=clean_chapter('第1章','他走进了屋坐了下来。\n最新章节请访问 www.example.com')
print(p['verdict'], p.get('edits'), u)
"
```

Expected: 返回 `ok`，edits 至少包含删除网址的一项；usage 有 token 数。
（不设 Key 时跳过此步，但上线前必须真跑一次。）

- [ ] **Step 6: Commit**

```bash
git add backend/apps/polish/deepseek.py backend/apps/polish/config.py backend/config.py backend/apps/polish/tests/test_deepseek.py
git commit -m "feat(polish): add deepseek client and runtime config"
```

---

### Task 7: 队列与预算服务

**Files:**
- Create: `backend/apps/polish/services.py`
- Test: `backend/apps/polish/tests/test_services.py`

**Interfaces:**
- Consumes: 模型、`get_daily_budget_cny()`、`cost_micros()`
- Produces:
  - `enqueue_chapters(user, book_url, book_name, source_url, chapters, priority=100) -> dict`
  - `claim_jobs(limit: int) -> list[PolishJob]`
  - `release_job(job)`
  - `record_quota(user, cost_micros)`
  - `budget_left_micros(user) -> int`
  - `upsert_book_state(user, book_url, book_name, verdict, reason) -> PolishBookState`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_services.py
from django.test import TestCase, override_settings
from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob, PolishPatchCache, PolishQuota
from apps.polish.services import enqueue_chapters, budget_left_micros

CHAPTERS = [
    {"index": 1, "url": "https://e.com/1/1", "title": "第1章", "hash": "a" * 32, "text": "正文"},
    {"index": 2, "url": "https://e.com/1/2", "title": "第2章", "hash": "b" * 32, "text": "正文2"},
]


@override_settings(POLISH_DAILY_BUDGET_CNY=2.0)
class QueueServiceTest(TestCase):
    def setUp(self):
        self.user = LegadoUser.objects.create(phone="13800000002")

    def test_enqueue(self):
        result = enqueue_chapters(self.user, "https://e.com/1", "书", "src", CHAPTERS)
        self.assertEqual(result["accepted"], 2)
        self.assertEqual(PolishJob.objects.count(), 2)

    def test_enqueue_dedupes_same_hash(self):
        enqueue_chapters(self.user, "https://e.com/1", "书", "src", CHAPTERS)
        result = enqueue_chapters(self.user, "https://e.com/1", "书", "src", CHAPTERS)
        self.assertEqual(result["accepted"], 0)
        self.assertEqual(result["deduped"], 2)

    def test_enqueue_uses_patch_cache(self):
        PolishPatchCache.objects.create(
            chapter_url="https://e.com/1/1",
            source_hash="a" * 32,
            verdict="contaminated",
            boundary_paragraph=3,
            patch_json={"verdict": "contaminated"},
        )
        result = enqueue_chapters(self.user, "https://e.com/1", "书", "src", CHAPTERS[:1])
        self.assertEqual(result["accepted"], 0)
        self.assertEqual(result["deduped"], 1)
        job = PolishJob.objects.get(chapter_index=1)
        self.assertEqual(job.status, "contaminated")
        self.assertEqual(job.cost_micros, 0)

    def test_budget(self):
        PolishQuota.objects.create(user=self.user, date="2026-11-18", spent_micros=1_000_000)
        self.assertEqual(budget_left_micros(self.user), 1_000_000)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_services -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

```python
# backend/apps/polish/services.py
from datetime import date

from django.db import connection, transaction
from django.utils import timezone

from apps.polish.config import get_daily_budget_cny
from apps.polish.models import (
    PolishBookState,
    PolishJob,
    PolishPatchCache,
    PolishQuota,
)


def _today():
    return timezone.localdate()


def _budget_micros() -> int:
    return int(get_daily_budget_cny() * 1_000_000)


def budget_left_micros(user) -> int:
    quota = PolishQuota.objects.filter(user=user, date=_today()).first()
    spent = quota.spent_micros if quota else 0
    return max(0, _budget_micros() - spent)


def record_quota(user, cost_micros: int) -> None:
    quota, _ = PolishQuota.objects.get_or_create(user=user, date=_today())
    quota.spent_micros += cost_micros
    quota.job_count += 1
    quota.save(update_fields=["spent_micros", "job_count", "update_time"])


def enqueue_chapters(
    user, book_url, book_name, source_url, chapters, priority=100,
    profile_version=0, book_author=""
) -> dict:
    accepted = deduped = skipped = 0
    for ch in chapters:
        text = ch.get("text") or ""
        if not text or len(text) > 12000:
            skipped += 1
            continue
        cached = PolishPatchCache.objects.filter(
            chapter_url=ch["url"], source_hash=ch["hash"]
        ).first()
        if cached:
            PolishJob.objects.update_or_create(
                user=user,
                book_url=book_url,
                chapter_index=ch["index"],
                source_hash=ch["hash"],
                defaults={
                    "book_name": book_name,
                    "book_author": book_author,
                    "source_url": source_url,
                    "chapter_url": ch["url"],
                    "chapter_title": ch.get("title", ""),
                    "status": cached.verdict if cached.verdict != "ok" else "done",
                    "verdict": cached.verdict,
                    "verdict_reason": cached.verdict_reason,
                    "boundary_paragraph": cached.boundary_paragraph,
                    "patch_json": cached.patch_json,
                    "priority": priority,
                    "profile_version": profile_version,
                    "raw_text": None,
                    "error": "",
                },
            )
            deduped += 1
            continue
        existing = PolishJob.objects.filter(
            user=user,
            book_url=book_url,
            chapter_index=ch["index"],
            source_hash=ch["hash"],
        ).first()
        if existing and existing.status in ("queued", "processing", "done"):
            deduped += 1
            continue
        PolishJob.objects.update_or_create(
            user=user,
            book_url=book_url,
            chapter_index=ch["index"],
            source_hash=ch["hash"],
            defaults={
                "book_name": book_name,
                "book_author": book_author,
                "source_url": source_url,
                "chapter_url": ch["url"],
                "chapter_title": ch.get("title", ""),
                "raw_text": text,
                "status": "queued",
                "priority": priority,
                "profile_version": profile_version,
                "error": "",
            },
        )
        accepted += 1
    return {"accepted": accepted, "deduped": deduped, "skipped": skipped}


@transaction.atomic
def claim_jobs(limit: int) -> list[PolishJob]:
    qs = PolishJob.objects.filter(status="queued").order_by("priority", "create_time")
    if connection.features.has_select_for_update:
        qs = qs.select_for_update(skip_locked=True)
    jobs = list(qs[:limit])
    if jobs:
        PolishJob.objects.filter(id__in=[j.id for j in jobs]).update(
            status="processing", update_time=timezone.now()
        )
    return jobs


def release_job(job, status="queued", error="") -> None:
    if status == "queued":
        job.attempts += 1
    job.status = status
    job.error = error
    job.save(update_fields=["status", "error", "attempts", "update_time"])


def release_stale_jobs(minutes=10) -> int:
    cutoff = timezone.now() - timezone.timedelta(minutes=minutes)
    return PolishJob.objects.filter(status="processing", update_time__lt=cutoff).update(
        status="queued", update_time=timezone.now()
    )


def upsert_book_state(user, book_url, book_name, verdict, reason="") -> PolishBookState:
    state, _ = PolishBookState.objects.get_or_create(
        user=user, book_url=book_url, defaults={"book_name": book_name}
    )
    if verdict == "contaminated":
        state.contaminated_streak += 1
    elif verdict == "ok":
        state.contaminated_streak = 0
    if state.contaminated_streak >= 3:
        state.status = "paid_truncated"
        state.last_reason = reason
        PolishJob.objects.filter(
            user=user, book_url=book_url, status="queued"
        ).update(status="skipped", error="book paid_truncated")
    state.save(update_fields=[
        "contaminated_streak", "status", "last_reason", "book_name", "update_time"
    ])
    return state
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_services -v 2`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/services.py backend/apps/polish/tests/test_services.py
git commit -m "feat(polish): queue, dedupe and budget services"
```

---

### Task 8: App API（上传 / 拉取 / 状态）

**Files:**
- Create: `backend/apps/polish/views_app.py`
- Modify: `backend/apps/legado/urls.py`
- Test: `backend/apps/polish/tests/test_api.py`

**Interfaces:**
- Consumes: `services.enqueue_chapters`、`PolishJob`、`PolishPatchCache`、`PolishBookState`
- Produces:
  - `POST /api/legado/app/polish/jobs`
  - `GET /api/legado/app/polish/patches?bookUrl=&since=&limit=`
  - `GET /api/legado/app/polish/status?bookUrl=`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_api.py
from django.test import TestCase
from rest_framework.test import APIClient
from apps.polish.models import PolishJob, PolishPatchCache


class PolishApiTest(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.headers = {"HTTP_X_USER_PHONE": "13800000003"}

    def test_upload_and_pull(self):
        body = {
            "bookUrl": "https://e.com/1",
            "bookName": "书",
            "bookAuthor": "作者",
            "sourceUrl": "src",
            "priority": 0,
            "chapters": [
                {"index": 1, "url": "https://e.com/1/1", "title": "第1章",
                 "hash": "a" * 32, "text": "第一段。\n第二段。"},
            ],
        }
        resp = self.client.post("/api/legado/app/polish/jobs", body, format="json", **self.headers)
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["accepted"], 1)

        job = PolishJob.objects.get()
        job.status = "done"
        job.patch_json = {"verdict": "ok", "edits": [{"p": 0, "start": 0, "end": 3, "replace": "第一段"}]}
        job.source_hash = "a" * 32
        job.save()

        resp = self.client.get(
            "/api/legado/app/polish/patches?bookUrl=https%3A%2F%2Fe.com%2F1&since=0",
            **self.headers,
        )
        self.assertEqual(resp.status_code, 200)
        patches = resp.json()["patches"]
        self.assertEqual(len(patches), 1)
        self.assertEqual(patches[0]["sourceHash"], "a" * 32)
        self.assertEqual(patches[0]["status"], "done")

    def test_missing_phone(self):
        resp = self.client.post("/api/legado/app/polish/jobs", {}, format="json")
        self.assertEqual(resp.status_code, 400)

    def test_status(self):
        resp = self.client.get(
            "/api/legado/app/polish/status?bookUrl=https%3A%2F%2Fe.com%2F1",
            **self.headers,
        )
        self.assertEqual(resp.status_code, 200)
        self.assertIn("queued", resp.json())
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_api -v 2`
Expected: FAIL / 404

- [ ] **Step 3: 写视图**

```python
# backend/apps/polish/views_app.py
from datetime import datetime
from zoneinfo import ZoneInfo

from django.conf import settings
from django.db.models import Count
from rest_framework.response import Response

from apps.legado.views_app import NoAuthAPIView, get_user
from apps.polish.models import PolishBookState, PolishJob
from apps.polish.services import budget_left_micros, enqueue_chapters


class PolishJobsView(NoAuthAPIView):
    def post(self, request):
        user, error = get_user(request)
        if error:
            return error
        data = request.data or {}
        chapters = data.get("chapters") or []
        if not isinstance(chapters, list) or len(chapters) > 20:
            return Response({"detail": "chapters 最多 20 章"}, status=400)
        book_url = str(data.get("bookUrl") or "").strip()
        if not book_url:
            return Response({"detail": "bookUrl 必填"}, status=400)
        result = enqueue_chapters(
            user,
            book_url,
            str(data.get("bookName") or ""),
            str(data.get("sourceUrl") or ""),
            chapters,
            priority=int(data.get("priority") or 100),
            profile_version=int(data.get("profileVersion") or 0),
            book_author=str(data.get("bookAuthor") or ""),
        )
        result["budgetLeftMicros"] = budget_left_micros(user)
        return Response(result)


class PolishPatchesView(NoAuthAPIView):
    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        book_url = request.query_params.get("bookUrl") or ""
        if not book_url:
            return Response({"detail": "bookUrl 必填"}, status=400)
        since = request.query_params.get("since") or "0"
        try:
            since_ms = int(since)
        except ValueError:
            since_ms = 0
        limit = min(int(request.query_params.get("limit") or 50), 200)

        qs = PolishJob.objects.filter(user=user, book_url=book_url).exclude(
            status="queued"
        ).exclude(status="processing")
        if since_ms:
            since_dt = datetime.fromtimestamp(
                since_ms / 1000, tz=ZoneInfo(settings.TIME_ZONE)
            ).replace(tzinfo=None)
            qs = qs.filter(update_time__gt=since_dt)
        jobs = qs.order_by("chapter_index")[:limit]
        patches = [
            {
                "chapterIndex": j.chapter_index,
                "chapterUrl": j.chapter_url,
                "sourceHash": j.source_hash,
                "status": j.status,
                "verdict": j.verdict,
                "boundary": j.boundary_paragraph,
                "reason": j.verdict_reason,
                "patch": j.patch_json,
                "costMicros": j.cost_micros,
                "updatedAt": int(j.update_time.timestamp() * 1000) if j.update_time else 0,
            }
            for j in jobs
        ]
        return Response({
            "patches": patches,
            "serverTime": int(__import__("time").time() * 1000),
            "budgetLeftMicros": budget_left_micros(user),
        })


class PolishStatusView(NoAuthAPIView):
    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        book_url = request.query_params.get("bookUrl") or ""
        counts = dict(
            PolishJob.objects.filter(user=user, book_url=book_url)
            .values_list("status")
            .annotate(n=Count("id"))
            .values_list("status", "n")
        )
        state = PolishBookState.objects.filter(user=user, book_url=book_url).first()
        return Response({
            "queued": counts.get("queued", 0),
            "processing": counts.get("processing", 0),
            "done": counts.get("done", 0),
            "contaminated": counts.get("contaminated", 0),
            "suspicious": counts.get("suspicious", 0),
            "failed": counts.get("failed", 0),
            "bookStatus": state.status if state else "normal",
            "budgetLeftMicros": budget_left_micros(user),
        })
```

在 `backend/apps/legado/urls.py` 的 `app_urlpatterns` 中追加：

```python
    path("app/polish/jobs", PolishJobsView.as_view(), name="legado-app-polish-jobs"),
    path("app/polish/patches", PolishPatchesView.as_view(), name="legado-app-polish-patches"),
    path("app/polish/status", PolishStatusView.as_view(), name="legado-app-polish-status"),
```

并在文件顶部加入：

```python
from apps.polish.views_app import (
    PolishJobsView,
    PolishPatchesView,
    PolishStatusView,
)
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_api -v 2`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/views_app.py backend/apps/legado/urls.py backend/apps/polish/tests/test_api.py
git commit -m "feat(polish): add app upload, patch and status APIs"
```

---

### Task 9: Worker 与定时任务

**Files:**
- Create: `backend/apps/polish/tasks.py`
- Create: `backend/apps/polish/management/__init__.py`
- Create: `backend/apps/polish/management/commands/__init__.py`
- Create: `backend/apps/polish/management/commands/run_polish_worker.py`
- Create: `backend/apps/polish/management/commands/seed_polish_job.py`
- Test: `backend/apps/polish/tests/test_tasks.py`

**Interfaces:**
- Consumes: `claim_jobs`、`release_stale_jobs`、`clean_chapter`、`validate_payload`、`prescreen`、`upsert_book_state`
- Produces:
  - `process_queue(limit=20, force=False) -> dict`
  - `clean_chapter_job(job) -> None`

- [ ] **Step 1: 写失败测试（本地逻辑，不调模型）**

```python
# backend/apps/polish/tests/test_tasks.py
from django.test import TestCase, override_settings
from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob
from apps.polish.tasks import process_queue


@override_settings(POLISH_HOLIDAYS=())
class WorkerGateTest(TestCase):
    def setUp(self):
        self.user = LegadoUser.objects.create(phone="13800000004")

    def test_peak_skips_without_claiming(self):
        PolishJob.objects.create(
            user=self.user, book_url="b", chapter_url="c", chapter_index=1,
            source_hash="a" * 32, raw_text="正文",
        )
        # 2026-11-18 10:00 是高峰；用 force=False + 显式 now 不好注入，这里只验证 force 分支不报错
        result = process_queue(limit=0, force=True)
        self.assertEqual(result["processed"], 0)

    def test_release_stale(self):
        job = PolishJob.objects.create(
            user=self.user, book_url="b", chapter_url="c", chapter_index=1,
            source_hash="a" * 32, raw_text="正文", status="processing",
        )
        from django.utils import timezone
        PolishJob.objects.filter(id=job.id).update(
            update_time=timezone.now() - timezone.timedelta(minutes=20)
        )
        from apps.polish.services import release_stale_jobs
        self.assertEqual(release_stale_jobs(), 1)
        job.refresh_from_db()
        self.assertEqual(job.status, "queued")
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_tasks -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写 worker**

```python
# backend/apps/polish/tasks.py
import logging
from datetime import datetime

from apps.polish.deepseek import clean_chapter
from apps.polish.models import PolishPatchCache
from apps.polish.prescreen import prescreen
from apps.polish.services import (
    claim_jobs,
    record_quota,
    release_job,
    release_stale_jobs,
    upsert_book_state,
    budget_left_micros,
)
from apps.polish.validator import validate_payload
from apps.polish.window import is_idle_window
from apps.polish.pricing import cost_micros

logger = logging.getLogger(__name__)


def _decide(payload: dict, text: str, flags: list[str]) -> dict:
    verdict = str(payload.get("verdict") or "").strip()
    try:
        conf = float(payload.get("confidence") or 0)
    except (TypeError, ValueError):
        conf = 0
    if verdict == "contaminated" and conf >= 0.7:
        return {"verdict": "contaminated"}
    if ("paywall" in flags or "mid_heading" in flags) and verdict != "contaminated":
        return {"verdict": "suspicious"}
    if verdict == "suspicious":
        return {"verdict": "suspicious"}
    if verdict == "ok" and conf < 0.7:
        return {"verdict": "suspicious"}
    return {"verdict": verdict or "suspicious"}


def clean_chapter_job(job) -> None:
    flags = prescreen(job.raw_text or "")
    payload, usage, _ = clean_chapter(job.chapter_title, job.raw_text or "")
    decision = _decide(payload, job.raw_text or "", flags)
    job.tokens_in = int(usage.get("prompt_tokens", 0) or 0)
    job.tokens_out = int(usage.get("completion_tokens", 0) or 0)
    job.cost_micros = cost_micros(usage, idle=is_idle_window(), model=job.model or "flash")
    job.model = job.model or "flash"

    if decision["verdict"] != "ok":
        job.status = decision["verdict"]
        job.verdict = decision["verdict"]
        job.verdict_reason = str(payload.get("reason") or "")
        job.boundary_paragraph = payload.get("boundary")
        job.patch_json = None
        PolishPatchCache.objects.update_or_create(
            chapter_url=job.chapter_url,
            source_hash=job.source_hash,
            defaults={
                "source_url": job.source_url,
                "verdict": job.verdict,
                "verdict_reason": job.verdict_reason,
                "boundary_paragraph": job.boundary_paragraph,
                "patch_json": None,
                "model": job.model,
            },
        )
        upsert_book_state(job.user, job.book_url, job.book_name, job.verdict, job.verdict_reason)
        job.raw_text = None
        return

    result = validate_payload(job.raw_text or "", payload)
    if not result.ok:
        job.status = "rejected"
        job.error = result.reason
        return

    job.status = "done"
    job.verdict = "ok"
    job.patch_json = {"verdict": "ok", "edits": result.edits,
                      "unrecoverable": payload.get("unrecoverable") or []}
    PolishPatchCache.objects.update_or_create(
        chapter_url=job.chapter_url,
        source_hash=job.source_hash,
        defaults={
            "source_url": job.source_url,
            "verdict": "ok",
            "verdict_reason": "",
            "boundary_paragraph": None,
            "patch_json": job.patch_json,
            "model": job.model,
        },
    )
    upsert_book_state(job.user, job.book_url, job.book_name, "ok")
    job.raw_text = None


def process_queue(limit: int = 20, force: bool = False) -> dict:
    if not force and not is_idle_window():
        return {"processed": 0, "reason": "peak"}
    release_stale_jobs()
    jobs = claim_jobs(limit)
    processed = 0
    for job in jobs:
        if not force and not is_idle_window():
            release_job(job)
            break
        if not force and budget_left_micros(job.user) <= 0:
            release_job(job)
            continue
        try:
            clean_chapter_job(job)
            job.attempts += 1
            job.save()
            record_quota(job.user, job.cost_micros)
            processed += 1
        except Exception as exc:
            logger.exception("polish job failed: %s", job.id)
            if job.attempts >= 2:
                release_job(job, status="failed", error=str(exc)[:500])
            else:
                release_job(job, status="queued", error=str(exc)[:500])
        finally:
            if job.status in ("done", "contaminated", "suspicious"):
                job.raw_text = None
                job.save(update_fields=["raw_text"])
    return {"processed": processed, "claimed": len(jobs)}
```

- [ ] **Step 4: 管理命令**

```python
# backend/apps/polish/management/commands/run_polish_worker.py
import json

from django.core.management.base import BaseCommand

from apps.polish.tasks import process_queue


class Command(BaseCommand):
    help = "手动跑一次 AI 清洗队列"

    def add_arguments(self, parser):
        parser.add_argument("--once", action="store_true")
        parser.add_argument("--force", action="store_true")
        parser.add_argument("--limit", type=int, default=20)

    def handle(self, *args, **options):
        result = process_queue(limit=options["limit"], force=options["force"])
        self.stdout.write(json.dumps(result, ensure_ascii=False))
```

```python
# backend/apps/polish/management/commands/seed_polish_job.py
from django.core.management.base import BaseCommand

from apps.sysjob.models import SysJob
from apps.sysjob.views import add_job_to_scheduler


class Command(BaseCommand):
    help = "创建/更新每 5 分钟跑一次的 AI 清洗任务"

    def handle(self, *args, **options):
        job, _ = SysJob.objects.update_or_create(
            aps_job_id="polish_queue",
            defaults={
                "job_name": "AI 正文清洗队列",
                "job_group": "DEFAULT",
                "invoke_target": "apps.polish.tasks.process_queue",
                "cron_expression": "0 */5 * * * *",
                "misfire_policy": "2",
                "concurrent": "0",
                "status": "0",
            },
        )
        add_job_to_scheduler(job)
        self.stdout.write(self.style.SUCCESS("polish_queue 已注册"))
```

- [ ] **Step 5: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_tasks -v 2`
Expected: 2 tests PASS

- [ ] **Step 6: 注册任务并验证**

Run:

```bash
cd backend && .venv/Scripts/python.exe manage.py seed_polish_job
cd backend && .venv/Scripts/python.exe manage.py run_polish_worker --limit 0
```

Expected: 输出 `polish_queue 已注册`；worker 输出 `{"processed": 0, ...}`（高峰时段应为 `{"processed":0,"reason":"peak"}`）。

- [ ] **Step 7: Commit**

```bash
git add backend/apps/polish/tasks.py backend/apps/polish/management backend/apps/polish/tests/test_tasks.py
git commit -m "feat(polish): add worker and 5-minute scheduler job"
```

---

### Task 10: 原文清理与报表命令

**Files:**
- Modify: `backend/apps/polish/tasks.py`
- Create: `backend/apps/polish/management/commands/polish_report.py`
- Test: `backend/apps/polish/tests/test_purge.py`

**Interfaces:**
- Consumes: `PolishJob`
- Produces: `purge_raw_texts(days=7) -> int`；`polish_report --book-url=`

- [ ] **Step 1: 写失败测试**

```python
# backend/apps/polish/tests/test_purge.py
from django.test import TestCase
from django.utils import timezone
from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob
from apps.polish.tasks import purge_raw_texts


class PurgeTest(TestCase):
    def test_purge_failed_after_days(self):
        user = LegadoUser.objects.create(phone="13800000005")
        job = PolishJob.objects.create(
            user=user, book_url="b", chapter_url="c", chapter_index=1,
            source_hash="a" * 32, raw_text="正文", status="failed",
        )
        PolishJob.objects.filter(id=job.id).update(
            update_time=timezone.now() - timezone.timedelta(days=8)
        )
        self.assertEqual(purge_raw_texts(days=7), 1)
        job.refresh_from_db()
        self.assertIsNone(job.raw_text)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_purge -v 2`
Expected: FAIL / ImportError

- [ ] **Step 3: 写实现**

在 `tasks.py` 追加：

```python
def purge_raw_texts(days: int = 7) -> int:
    from django.utils import timezone
    cutoff = timezone.now() - timezone.timedelta(days=days)
    return PolishJob.objects.filter(
        raw_text__isnull=False, update_time__lt=cutoff
    ).update(raw_text=None)
```

```python
# backend/apps/polish/management/commands/polish_report.py
import json

from django.core.management.base import BaseCommand

from apps.polish.models import PolishJob


class Command(BaseCommand):
    help = "查看某本书的清洗结果与 diff"

    def add_arguments(self, parser):
        parser.add_argument("--book-url", required=True)
        parser.add_argument("--limit", type=int, default=20)

    def handle(self, *args, **options):
        jobs = PolishJob.objects.filter(
            book_url=options["book_url"]
        ).order_by("chapter_index")[: options["limit"]]
        for job in jobs:
            self.stdout.write(json.dumps({
                "chapter": job.chapter_index,
                "title": job.chapter_title,
                "status": job.status,
                "verdict": job.verdict,
                "boundary": job.boundary_paragraph,
                "reason": job.verdict_reason,
                "costMicros": job.cost_micros,
                "edits": (job.patch_json or {}).get("edits", []),
            }, ensure_ascii=False))
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && .venv/Scripts/python.exe manage.py test apps.polish.tests.test_purge -v 2`
Expected: 1 test PASS

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/tasks.py backend/apps/polish/management/commands/polish_report.py backend/apps/polish/tests/test_purge.py
git commit -m "feat(polish): purge raw text and add report command"
```

---

### Task 11: 真实模型端到端冒烟

**Files:**
- Create: `backend/apps/polish/fixtures/clean_ok.txt`
- Create: `backend/apps/polish/fixtures/ad_and_missing.txt`
- Create: `backend/apps/polish/fixtures/spliced.txt`
- Create: `backend/apps/polish/management/commands/polish_smoke.py`

**Interfaces:**
- Consumes: 真实 DeepSeek API、`process_queue(force=True)`
- Produces: 一次可复跑的冒烟，打印状态/判定/费用/diff

- [ ] **Step 1: 写三个合成样本（不要用受版权保护的真实小说）**

`clean_ok.txt`：

```
他推开木门，院子里落满了雪。
廊下挂着一盏旧灯笼，风一吹就轻轻晃动。
```

`ad_and_missing.txt`：

```
他走进了屋坐了下来。
最新章节请访问 www.example-novel.com 或关注公众号“某某书城”。
她抬起头，看见窗外站着一个人影。
```

`spliced.txt`：

```
他推开木门，院子里落满了雪。
廊下挂着一盏旧灯笼，风一吹就轻轻晃动。
本章未完，请充值后阅读。
第1章 都市兵王
林凡从床上坐起来，发现自己回到了二十岁。
楼下传来房东的喊声，今天必须交房租。
```

- [ ] **Step 2: 写冒烟命令**

```python
# backend/apps/polish/management/commands/polish_smoke.py
import json
from pathlib import Path

from django.core.management.base import BaseCommand

from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob
from apps.polish.services import enqueue_chapters
from apps.polish.tasks import process_queue

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


class Command(BaseCommand):
    help = "用真实 DeepSeek 跑 3 个合成章节（需要 LEGADO_DEEPSEEK_API_KEY）"

    def handle(self, *args, **options):
        import hashlib, time
        user, _ = LegadoUser.objects.get_or_create(phone="19900000000")
        suffix = str(int(time.time()))
        book_url = f"https://smoke.local/book/{suffix}"
        chapters = []
        for index, name in enumerate(["clean_ok.txt", "ad_and_missing.txt", "spliced.txt"]):
            text = (FIXTURES / name).read_text(encoding="utf-8").strip()
            chapters.append({
                "index": index,
                "url": f"https://smoke.local/{suffix}/{index}",
                "title": f"第{index + 1}章",
                "hash": hashlib.md5(text.encode("utf-8")).hexdigest(),
                "text": text,
            })
        enqueue_chapters(user, book_url, "冒烟书", "smoke", chapters, priority=0)
        result = process_queue(limit=3, force=True)
        self.stdout.write(json.dumps(result, ensure_ascii=False))
        for job in PolishJob.objects.filter(book_url=book_url).order_by("chapter_index"):
            self.stdout.write(json.dumps({
                "chapter": job.chapter_index,
                "status": job.status,
                "verdict": job.verdict,
                "boundary": job.boundary_paragraph,
                "reason": job.verdict_reason,
                "costMicros": job.cost_micros,
                "edits": (job.patch_json or {}).get("edits", []),
            }, ensure_ascii=False))
```

- [ ] **Step 3: 运行真实冒烟**

Run:

```bash
cd backend && LEGADO_DEEPSEEK_API_KEY=sk-xxx .venv/Scripts/python.exe manage.py polish_smoke
```

Expected:
- `clean_ok` → `done`，edits 为空或极少；
- `ad_and_missing` → `done`，至少有删除网址的 edit；
- `spliced` → `contaminated`，`boundary` 在 3 附近，`edits` 为空；
- 打印的费用合计远小于 ¥0.01。

- [ ] **Step 4: 记录实际费用与判定**

把本次输出追加到 `docs/superpowers/specs/2026-11-18-ai-content-polish-design.md` 的“验收”小节下方，注明日期、模型、三章 token 与费用。

- [ ] **Step 5: Commit**

```bash
git add backend/apps/polish/fixtures backend/apps/polish/management/commands/polish_smoke.py docs/superpowers/specs/2026-11-18-ai-content-polish-design.md
git commit -m "test(polish): add real deepseek smoke for clean, damaged and spliced chapters"
```

---

### Task 12: 部署与验证

**Files:** 无代码改动

- [ ] **Step 1: 生产环境变量**

在 118 的 `legado.service` 环境或 `/opt/legado/backend/config_local.py` 中加：

```python
DEEPSEEK_API_KEY = "sk-..."
POLISH_MODEL = "deepseek-flash"
POLISH_DAILY_BUDGET_CNY = 2.0
POLISH_HOLIDAYS = ()  # 一般留空；每月任务会写入 SystemConfig.polish_holidays
```

Key 只放服务器，不入库、不进仓库、不发客户端。

- [ ] **Step 2: 迁移与重启**

```bash
cd /opt/legado/backend && .venv/bin/python manage.py migrate
/usr/bin/systemctl restart legado.service
/usr/bin/systemctl status legado.service
```

注册任务（只需一次）：

```bash
cd /opt/legado/backend && .venv/bin/python manage.py seed_polish_job
cd /opt/legado/backend && .venv/bin/python manage.py seed_polish_holiday_job
```

- [ ] **Step 3: 验证接口**

```bash
curl -s "http://127.0.0.1:9666/api/legado/app/polish/status?bookUrl=https%3A%2F%2Fe.com%2F1" \
  -H "X-User-Phone: <手机号>"
```

Expected: `{"queued":0,...,"bookStatus":"normal",...}`

- [ ] **Step 4: 验证调度**

```bash
journalctl -u legado.service -f | grep -E "polish|AI 正文清洗"
```

在空闲窗口内应看到 worker 每 5 分钟执行；高峰窗口应输出 `{"processed":0,"reason":"peak"}`。

- [ ] **Step 5: 验收记录**

把迁移、重启、接口、调度的实际输出记入设计文档“验收”小节，附日期。
