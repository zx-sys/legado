import hashlib
from datetime import date, timedelta

from django.conf import settings
from django.db import connection, transaction
from django.utils import timezone

from apps.polish.config import get_daily_budget_cny
from apps.polish.models import (
    PolishBookState,
    PolishJob,
    PolishPatchCache,
    PolishQuota,
)
from apps.polish.profile_samples import maybe_store_sample


def _today() -> date:
    return date.today()


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
    book_key = hashlib.md5(
        f"{book_name}|{book_author}".encode("utf-8")
    ).hexdigest()
    learning_enabled = getattr(settings, "POLISH_PROFILE_LEARNING_ENABLED", True)
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
        if learning_enabled:
            maybe_store_sample(
                source_url, book_key, book_url, ch["url"], ch["index"],
                ch.get("title", ""), text,
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
    cutoff = timezone.now() - timedelta(minutes=minutes)
    return PolishJob.objects.filter(
        status="processing", update_time__lt=cutoff
    ).update(status="queued", update_time=timezone.now())


def upsert_book_state(user, book_url, book_name, verdict, reason="") -> PolishBookState:
    state, _ = PolishBookState.objects.get_or_create(
        user=user, book_url=book_url, defaults={"book_name": book_name}
    )
    if verdict == "contaminated":
        state.contaminated_streak += 1
    elif verdict == "ok":
        state.contaminated_streak = 0
        if state.status == "paid_truncated":
            state.status = "normal"
    if state.contaminated_streak >= 3:
        state.status = "paid_truncated"
        state.last_reason = reason
        PolishJob.objects.filter(
            user=user, book_url=book_url, status="queued"
        ).update(status="skipped", error="book paid_truncated")
    state.save(
        update_fields=[
            "contaminated_streak",
            "status",
            "last_reason",
            "book_name",
            "update_time",
        ]
    )
    return state
