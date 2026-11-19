import logging
from datetime import timedelta

from django.utils import timezone

from apps.polish.deepseek import clean_chapter
from apps.polish.models import PolishPatchCache, PolishJob
from apps.polish.prescreen import guess_boundary, prescreen
from apps.polish.pricing import cost_micros
from apps.polish.services import (
    budget_left_micros,
    claim_jobs,
    record_quota,
    release_job,
    release_stale_jobs,
    upsert_book_state,
)
from apps.polish.validator import validate_payload
from apps.polish.window import is_idle_window

logger = logging.getLogger(__name__)


def _decide(payload: dict, flags: list[str]) -> dict:
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


def clean_chapter_job(job: PolishJob) -> None:
    flags = prescreen(job.raw_text or "")
    payload, usage, _ = clean_chapter(job.chapter_title, job.raw_text or "")
    decision = _decide(payload, flags)
    job.tokens_in = int(usage.get("prompt_tokens", 0) or 0)
    job.tokens_out = int(usage.get("completion_tokens", 0) or 0)
    job.model = job.model or "flash"
    job.cost_micros = cost_micros(usage, idle=is_idle_window(), model=job.model)

    if decision["verdict"] != "ok":
        job.status = decision["verdict"]
        job.verdict = decision["verdict"]
        job.verdict_reason = str(payload.get("reason") or "")
        boundary = payload.get("boundary")
        if boundary is None:
            boundary = guess_boundary(job.raw_text or "")
        try:
            boundary = int(boundary) if boundary is not None else None
        except (TypeError, ValueError):
            boundary = None
        job.boundary_paragraph = boundary
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
        upsert_book_state(
            job.user, job.book_url, job.book_name, job.verdict, job.verdict_reason
        )
        job.raw_text = None
        return

    result = validate_payload(job.raw_text or "", payload)
    if not result.ok:
        job.status = "rejected"
        job.error = result.reason
        return

    job.status = "done"
    job.verdict = "ok"
    job.patch_json = {
        "verdict": "ok",
        "edits": result.edits,
        "unrecoverable": payload.get("unrecoverable") or [],
    }
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


def purge_raw_texts(days: int = 7) -> int:
    cutoff = timezone.now() - timedelta(days=days)
    return PolishJob.objects.filter(
        raw_text__isnull=False, update_time__lt=cutoff
    ).update(raw_text=None)
